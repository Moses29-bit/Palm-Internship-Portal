import csv
import io
import json
import os
import pathlib
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]


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
        return response.status, response.headers, response.read()

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
        base = os.environ.get("PALM_BASE_URL")
        if not base:
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

    def test_onboarding_geofence_privacy_exports_and_footer(self):
        student = login(self.base, self.students[0]["email"], self.student_password)
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
