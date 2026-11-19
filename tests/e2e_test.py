import csv
import email.policy
import io
import json
import os
import pathlib
import re
import shutil
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]


class CapturingSMTPHandler(socketserver.StreamRequestHandler):
    def handle(self):
        self.reply("220 localhost ESMTP")
        auth_step = 0
        collecting = False
        message = []
        while True:
            line = self.rfile.readline()
            if not line:
                return
            command = line.decode("utf-8", errors="replace").rstrip("\r\n")
            if collecting:
                if command == ".":
                    failed = False
                    with self.server.messages_lock:
                        raw_message = "\r\n".join(message)
                        if self.server.fail_recipient and self.server.fail_recipient.lower() in raw_message.lower():
                            self.server.fail_recipient = None
                            failed = True
                        else:
                            self.server.messages.append(raw_message)
                    collecting = False
                    message = []
                    self.reply("554 Delivery rejected" if failed else "250 Message accepted")
                else:
                    message.append(command)
            elif command.upper().startswith(("EHLO ", "HELO ")):
                self.reply("250 localhost")
            elif command.upper() == "AUTH LOGIN":
                auth_step = 1
                self.reply("334 VXNlcm5hbWU6")
            elif auth_step == 1:
                auth_step = 2
                self.reply("334 UGFzc3dvcmQ6")
            elif auth_step == 2:
                auth_step = 0
                self.reply("235 Authentication successful")
            elif command.upper().startswith(("MAIL FROM:", "RCPT TO:")):
                self.reply("250 Accepted")
            elif command.upper() == "DATA":
                collecting = True
                self.reply("354 End data with <CR><LF>.<CR><LF>")
            elif command.upper() == "QUIT":
                self.reply("221 Bye")
                return
            else:
                self.reply("250 Accepted")

    def reply(self, text):
        self.wfile.write((text + "\r\n").encode())
        self.wfile.flush()


class CapturingSMTPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address):
        super().__init__(address, CapturingSMTPHandler)
        self.messages = []
        self.messages_lock = threading.Lock()
        self.fail_recipient = None


class Portal:
    def __init__(self, base, token="", csrf=""):
        self.base = base.rstrip("/")
        self.token = token
        self.csrf = csrf

    def request(self, method, path, values=None, raw=None):
        headers = {}
        body = raw
        if values is not None:
            body = urllib.parse.urlencode(values).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if method == "POST" and self.csrf:
            headers["X-CSRF"] = self.csrf
        request = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        try:
            return response.status, response.headers, response.read()
        finally:
            response.close()

    def post(self, path, values):
        status, _, body = self.request("POST", path, values)
        try:
            result = json.loads(body)
        except (ValueError, TypeError):
            result = {"body": body.decode(errors="replace")}
        return status, result

    def get_json(self, path):
        status, _, body = self.request("GET", path)
        return status, json.loads(body)


def login(base, username, password):
    status, _, body = Portal(base).request("POST", "/api/login", {"username": username, "password": password})
    result = json.loads(body)
    if status != 200:
        raise AssertionError("Login failed: " + json.dumps(result))
    return Portal(base, result["token"], result["csrf"])


class PortalE2E(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.process = None
        cls.tempdir = None
        cls.smtp_server = None
        cls.smtp_thread = None
        base = os.environ.get("PALM_BASE_URL")
        if not base:
            cls.smtp_server = CapturingSMTPServer(("127.0.0.1", 0))
            cls.smtp_thread = threading.Thread(target=cls.smtp_server.serve_forever, daemon=True)
            cls.smtp_thread.start()
            cls.tempdir = pathlib.Path(tempfile.mkdtemp(prefix="palm-e2e-"))
            cls.addClassCleanup(cls.cleanup)
            classes = cls.tempdir / "classes"
            classes.mkdir()
            release = os.environ.get("PALM_JAVA_RELEASE", "25")
            subprocess.run(
                ["javac", "--release", release, "-d", str(classes), str(ROOT / "Server.java"), str(ROOT / "tests" / "FakeDb.java")],
                check=True,
                cwd=ROOT,
            )
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                port = sock.getsockname()[1]
            cls.base = "http://127.0.0.1:" + str(port)
            admin_password = os.environ.get("ADMIN_PASSWORD", "PortalTestPassword123!")
            dialect = os.environ.get("PALM_DIALECT", "mariadb").lower()
            if dialect not in ("mariadb", "postgres"):
                raise ValueError("PALM_DIALECT must be mariadb or postgres")
            database_url = "jdbc:mariadb://fake/palm" if dialect == "mariadb" else "jdbc:postgresql://fake/palm"
            main_class = os.environ.get("PALM_MAIN", "Server")
            env = os.environ.copy()
            env.update({
                "ADMIN_PASSWORD": admin_password,
                "DATABASE_URL": database_url,
                "PORT": str(port),
                "BIND": "127.0.0.1",
                "PRODUCTION": "false",
                "ALLOW_FILE_MODE": "false",
                "SMTP_HOST": "127.0.0.1",
                "SMTP_PORT": str(cls.smtp_server.server_address[1]),
                "SMTP_SECURITY": "none",
                "SMTP_USER": "test@example.test",
                "SMTP_PASS": "test-password",
                "SMTP_FROM": "test@example.test",
                "VERIFICATION_HMAC_SECRET": "test-only-verification-secret-32-bytes",
            })
            java = os.environ.get("JAVA", "java")
            cp = str(classes) + os.pathsep + str(ROOT / "tests")
            cls.process = subprocess.Popen(
                [java, "-Djdbc.drivers=FakeDb", "-cp", cp, main_class],
                cwd=ROOT,
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            cls.admin_password = admin_password
            for _ in range(60):
                if cls.process.poll() is not None:
                    raise RuntimeError("Portal server exited during startup: " + cls.process.stderr.read().decode(errors="replace"))
                try:
                    urllib.request.urlopen(cls.base + "/api/ping", timeout=1).read()
                    break
                except Exception:
                    time.sleep(0.25)
            else:
                raise RuntimeError("Portal server did not become ready")
        else:
            cls.base = base
            cls.admin_password = os.environ.get("ADMIN_PASSWORD")
            if not cls.admin_password:
                raise ValueError("Set ADMIN_PASSWORD when PALM_BASE_URL is used")
        cls.admin = login(cls.base, os.environ.get("ADMIN_USERNAME", "admin"), cls.admin_password)
        cls.company_password = "CompanyOnboardedPass123!"
        cls.student_password = "StudentPortalPass123!"
        suffix = uuid.uuid4().hex[:8]
        cls.suffix = suffix
        cls.company1 = cls.create_company("Alpha Workplace " + suffix)
        cls.company2 = cls.create_company("No Location Workplace " + suffix)
        cls.complete_onboarding(cls.company1)
        emails = [
            ("zulu." + suffix + "@example.test", "Zulu Student " + suffix),
            ("alpha." + suffix + "@example.test", "Alpha Student " + suffix),
        ]
        cls.admin.post("/api/register", {"mode": "merge", "list": "\n".join(email + "," + name for email, name in emails)})
        cls.students = []
        for email, name in emails:
            status, result = Portal(cls.base).post("/api/signup", {
                "name": name, "program": "Computer Science", "email": email,
                "password": cls.student_password, "consent": "1",
            })
            if status != 200 or not result.get("ok"):
                raise AssertionError("Could not register test student: " + json.dumps(result))
            if cls.smtp_server:
                if not result.get("verify"):
                    raise AssertionError("Signup did not require email verification")
                pending_status, pending_result = Portal(cls.base).post("/api/login", {
                    "username": email, "password": cls.student_password,
                })
                if pending_status != 403:
                    raise AssertionError("Unverified account was not blocked from login: " + json.dumps(pending_result))
                code = cls.verification_code(email)
                wrong = f"{(int(code) + 1) % 1_000_000:06d}"
                wrong_status, _ = Portal(cls.base).post("/api/verify", {"username": email, "code": wrong})
                if wrong_status != 400:
                    raise AssertionError("Incorrect verification code was accepted")
                verify_status, verify_result = Portal(cls.base).post("/api/verify", {
                    "username": email, "code": code,
                })
                if verify_status != 200 or not verify_result.get("ok"):
                    raise AssertionError("Could not verify test student: " + json.dumps(verify_result))
        _, data = cls.admin.get_json("/api/data")
        for email, name in emails:
            cls.students.append(next(student for student in data["students"] if student["email"] == email))
        cls.admin.post("/api/assign", {"sid": cls.students[0]["id"], "cid": cls.company1["id"]})
        cls.admin.post("/api/status", {"sid": cls.students[0]["id"], "status": "ACTIVE"})
        cls.admin.post("/api/assign", {"sid": cls.students[1]["id"], "cid": cls.company2["id"]})
        cls.admin.post("/api/status", {"sid": cls.students[1]["id"], "status": "ACTIVE"})
        cls.admin.post("/api/company/location", {"cid": cls.company1["id"], "address": "Palm test workplace", "location": "0,0"})

    @classmethod
    def create_company(cls, name):
        status, result = cls.admin.post("/api/company", {"name": name, "industry": "Technology", "focus": "Software", "capacity": "5", "email": "contact@example.test"})
        if status != 200 or not result.get("login"):
            raise AssertionError("Could not create test company: " + json.dumps(result))
        _, data = cls.admin.get_json("/api/data")
        result["id"] = next(company["id"] for company in data["companies"] if company["name"] == name)
        return result

    @classmethod
    def complete_onboarding(cls, company):
        client = login(cls.base, company["login"], company["password"])
        status, result = client.post("/api/post", {"type": "news", "title": "Blocked", "body": "Must remain blocked"})
        if status != 403 or result.get("code") != "ONBOARDING_REQUIRED":
            raise AssertionError("Company onboarding gate did not block a restricted action")
        status, result = client.post("/api/password", {"old": company["password"], "password": cls.company_password})
        if status != 200 or not result.get("ok"):
            raise AssertionError("Company password update failed: " + json.dumps(result))
        client.post("/api/profile", {"name": company["name"], "industry": "Technology", "about": "A test workplace for interns.", "email": "contact@example.test", "website": ""})
        client.post("/api/company/location", {"address": "Palm test workplace", "location": "0,0"})
        status, result = client.post("/api/onboarding/confirm", {})
        if status != 200 or not result.get("ok"):
            raise AssertionError("Company onboarding confirmation failed: " + json.dumps(result))
        status, data = client.get_json("/api/data")
        if status != 200 or data["me"]["mustOnboard"]:
            raise AssertionError("Company onboarding remained active after confirmation")
        status, result = client.post("/api/post", {
            "type": "UPDATE", "title": "Onboarding complete", "body": "Company access is available",
        })
        if status != 200 or not result.get("ok"):
            raise AssertionError("Company actions remained blocked after onboarding: " + json.dumps(result))
        return client

    @classmethod
    def cleanup(cls):
        if cls.process and cls.process.poll() is None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait()
        if cls.tempdir:
            shutil.rmtree(cls.tempdir)
        if cls.smtp_server:
            cls.smtp_server.shutdown()
            cls.smtp_server.server_close()
            cls.smtp_thread.join(timeout=5)

    @classmethod
    def verification_code(cls, recipient):
        with cls.smtp_server.messages_lock:
            messages = list(cls.smtp_server.messages)
        for raw_message in reversed(messages):
            message = email.message_from_string(raw_message, policy=email.policy.default)
            if recipient.lower() not in str(message.get("To", "")).lower():
                continue
            match = re.search(r"\b(\d{6})\b", message.get_content())
            if match:
                return match.group(1)
        raise AssertionError("Verification email was not captured for " + recipient)

    def test_onboarding_geofence_privacy_exports_and_footer(self):
        student = login(self.base, self.students[0]["email"], self.student_password)
        status, result = self.admin.post("/api/company/location", {
            "cid": self.company1["id"], "address": "Palm test workplace", "location": "100,20",
        })
        self.assertEqual(status, 400, result)

        status, result = self.admin.post("/api/settings", {"geofenceAccuracyMeters": "50"})
        self.assertEqual(status, 200, result)
        fix_time = int(time.time() * 1000)
        status, result = student.post("/api/clock", {
            "action": "in", "lat": "0", "lng": "0", "accuracy": "51", "timestamp": str(fix_time),
        })
        self.assertEqual(status, 400, result)
        status, result = self.admin.post("/api/settings", {"geofenceAccuracyMeters": "100"})
        self.assertEqual(status, 200, result)
        now = int(time.time() * 1000)
        for fix, expected in [
            ({"lat": "0", "lng": "0.00135", "accuracy": "5", "timestamp": str(now)}, 400),
            ({"lat": "0", "lng": "0", "accuracy": "5", "timestamp": str(now - 61000)}, 400),
            ({"lat": "0", "lng": "0", "accuracy": "101", "timestamp": str(now)}, 400),
            ({"lat": "0", "lng": "0.000809", "accuracy": "5", "timestamp": str(now)}, 200),
        ]:
            status, result = student.post("/api/clock", {"action": "in", **fix})
            self.assertEqual(status, expected, result)
        status, result = student.post("/api/clock", {"action": "out"})
        self.assertEqual(status, 200, result)

        no_location = login(self.base, self.students[1]["email"], self.student_password)
        status, result = no_location.post("/api/clock", {"action": "in"})
        self.assertEqual(status, 400)
        self.assertIn("workplace location", result.get("error", "").lower())

        for client in (student, login(self.base, self.company1["login"], self.company_password)):
            status, result = client.get_json("/api/evaluation-report")
            self.assertEqual(status, 403, result)
            status, data = client.get_json("/api/data")
            self.assertEqual(status, 200)
            self.assertNotIn('"answers"', json.dumps(data))

        status, headers, body = self.admin.request("GET", "/api/export?type=students&sort=name&direction=asc")
        self.assertEqual(status, 200)
        names = [row[1] for row in csv.reader(io.StringIO(body.decode("utf-8-sig")))]
        self.assertLess(names.index(self.students[1]["name"]), names.index(self.students[0]["name"]))
        status, _, body = self.admin.request("GET", "/api/export?type=students&sort=email&direction=asc")
        emails = [row[2] for row in csv.reader(io.StringIO(body.decode("utf-8-sig")))]
        self.assertLess(emails.index(self.students[1]["email"]), emails.index(self.students[0]["email"]))

        query = urllib.parse.urlencode({"type": "attendance", "search": self.students[0]["name"], "status": "completed", "sort": "name"})
        status, _, body = self.admin.request("GET", "/api/export?" + query)
        exported = list(csv.reader(io.StringIO(body.decode("utf-8-sig"))))
        self.assertEqual(len(exported), 2)
        self.assertEqual(exported[1][0], self.students[0]["name"])
        self.assertIn("Distance (m)", exported[0])

        status, headers, body = self.admin.request("GET", "/api/export?type=attendance&format=print")
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers.get("Content-Type", ""))
        self.assertIn(b"Print / Save as PDF", body)
        status, _, body = Portal(self.base).request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn(b"Developed by Group 4", body)

    def test_signup_remains_pending_when_email_delivery_fails(self):
        if not self.smtp_server:
            self.skipTest("Requires the local SMTP capture server")
        email = "delivery.failure." + self.suffix + "@example.test"
        self.admin.post("/api/register", {
            "mode": "merge", "list": email + ",Delivery Failure Test",
        })
        with self.smtp_server.messages_lock:
            self.smtp_server.fail_recipient = email
        status, result = Portal(self.base).post("/api/signup", {
            "name": "Delivery Failure Test", "program": "Computer Science",
            "email": email, "password": self.student_password, "consent": "1",
        })
        self.assertEqual(status, 400, result)
        self.assertIn("could not be sent", result.get("error", "").lower())
        status, result = Portal(self.base).post("/api/login", {
            "username": email, "password": self.student_password,
        })
        self.assertEqual(status, 403, result)

    def test_verification_code_stops_after_five_incorrect_attempts(self):
        if not self.smtp_server:
            self.skipTest("Requires the local SMTP capture server")
        email = "attempt.limit." + self.suffix + "@example.test"
        self.admin.post("/api/register", {
            "mode": "merge", "list": email + ",Attempt Limit Test",
        })
        status, result = Portal(self.base).post("/api/signup", {
            "name": "Attempt Limit Test", "program": "Computer Science",
            "email": email, "password": self.student_password, "consent": "1",
        })
        self.assertEqual(status, 200, result)
        code = self.verification_code(email)
        wrong = f"{(int(code) + 1) % 1_000_000:06d}"
        for _ in range(5):
            status, _ = Portal(self.base).post("/api/verify", {"username": email, "code": wrong})
            self.assertEqual(status, 400)
        status, result = Portal(self.base).post("/api/verify", {"username": email, "code": code})
        self.assertEqual(status, 400, result)
        status, result = Portal(self.base).post("/api/login", {
            "username": email, "password": self.student_password,
        })
        self.assertEqual(status, 403, result)


if __name__ == "__main__":
    unittest.main(verbosity=2)
