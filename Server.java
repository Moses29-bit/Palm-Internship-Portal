import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.time.LocalDate;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Palm Internship Placement Portal v2: roles (ADMIN / STUDENT / COMPANY), application workflow, letters, CSV. */
public class Server {
    static final Map<String, String> FILE_ENV = loadDotEnv();
    static String env(String name) {
        String value = System.getenv(name);
        return value == null ? FILE_ENV.get(name) : value;
    }
    static String env(String name, String fallback) {
        String value = env(name);
        return value == null ? fallback : value;
    }
    static Map<String, String> loadDotEnv() {
        Map<String, String> values = new HashMap<>();
        for (Path file : List.of(Path.of(".env"), Path.of(".env.local"))) {
            if (!Files.exists(file)) continue;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String entry = line.trim();
                    if (entry.isEmpty() || entry.startsWith("#")) continue;
                    if (entry.startsWith("export ")) entry = entry.substring(7).trim();
                    int separator = entry.indexOf('=');
                    if (separator < 1) continue;
                    String name = entry.substring(0, separator).trim();
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                    String value = entry.substring(separator + 1).trim();
                    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        char quote = value.charAt(0);
                        value = value.substring(1, value.length() - 1);
                        if (quote == '"') value = value.replace("\\n", "\n").replace("\\r", "\r");
                    } else {
                        int comment = value.indexOf(" #");
                        if (comment >= 0) value = value.substring(0, comment).trim();
                    }
                    values.put(name, value);
                }
            } catch (IOException e) {
                throw new ExceptionInInitializerError("Could not read " + file + ": " + e.getMessage());
            }
        }
        return Collections.unmodifiableMap(values);
    }
    record User(String name, String role, String ref, byte[] salt, byte[] hash, boolean mustOnboard, boolean passwordChanged) {}
    record Student(String id, String name, String program, String email) { Student(String id, String name, String program) { this(id, name, program, ""); } }
    record Company(String id, String name, String industry, String focus, int capacity, String email, String website, String about,
                   Double locationLat, Double locationLng, String locationAddress, String mapsLink, String locationSetOn, String locationSetBy) {
        Company(String id, String name, String industry, String focus, int capacity, String email, String website, String about) {
            this(id, name, industry, focus, capacity, email, website, about, null, null, "", "", "", "");
        }
        Company(String id, String name, String industry, String focus, int capacity) {
            this(id, name, industry, focus, capacity, "", "", "");
        }
    }
    record Session(String csrf, long exp, User u) {}
    record Match(Company c, int score, String why) {}
    static class Placement {
        String sid, cid, status, date, start = "", end = "";
        Placement(String s, String c, String st, String d) { sid = s; cid = c; status = st; date = d; }
    }
    /** SUBMITTED -> (school) FORWARDED | REJECTED -> (company) ACCEPTED | DECLINED */
    static class App {
        String id, sid, cid, statement, status, date, pref = "", schoolNote = "", companyNote = "";
        App(String i, String s, String c, String t, String st, String d) { id = i; sid = s; cid = c; statement = t; status = st; date = d; }
    }

    static final List<Student> students = new ArrayList<>();
    static final List<Company> companies = new ArrayList<>();
    static final List<Placement> placements = new ArrayList<>();
    static final List<App> apps = new ArrayList<>();
    static final Map<String, User> users = new ConcurrentHashMap<>();
    static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    static final Map<String, long[]> fails = new ConcurrentHashMap<>();
    static final Set<String> STATUSES = Set.of("PENDING", "ACTIVE", "COMPLETED", "CANCELLED");
    static final Set<String> COMPANY_SEES = Set.of("FORWARDED", "ACCEPTED", "DECLINED");
    static final Set<String> ADMIN_POST = Set.of("/api/student", "/api/company", "/api/assign", "/api/status", "/api/review", "/api/message", "/api/resetpw", "/api/staff", "/api/settings", "/api/register", "/api/access", "/api/erase", "/api/onboarding/reset", "/api/evaluation-forms", "/api/evaluation-forms/duplicate", "/api/evaluation-forms/archive");
    static final Set<String> ADMIN_GET = Set.of("/api/match", "/api/export", "/api/evaluation-report");
    static final Set<String> STOP = Set.of("and", "the", "for", "bsc", "msc", "hnd", "of", "with", "from", "that", "this", "have", "has", "was", "were", "are", "not", "you", "your", "our", "their", "can", "all", "any", "who", "also", "into", "been", "will", "palm", "ghana", "university", "college");
    static final Map<String, String> STATIC = Map.of("/", "index.html", "/app.js", "app.js", "/style.css", "style.css",
            "/logo.png", "logo.png", "/hero.jpg", "hero.jpg", "/hero.svg", "hero.svg", "/puc.png", "puc.png");
    static Path WEB = Path.of("static");
    static final SecureRandom RNG = new SecureRandom();
    static final Path DB = Path.of(env("DATA_FILE", "data.tsv"));
    static final Path CVS = Path.of("cvs");
    static final String OK = "{\"ok\":true}";
    static User DUMMY;
    static final long STARTED = System.currentTimeMillis();

    public static void main(String[] a) throws Exception {
        String pw = env("ADMIN_PASSWORD");
        if (PRODUCTION && (pw == null || pw.length() < 12)) { System.err.println("PRODUCTION mode needs ADMIN_PASSWORD with at least 12 characters."); System.exit(1); }
        if (pw == null || pw.length() < 8) { pw = tempPass(); System.out.println("Generated admin password: " + pw); }
        DUMMY = newUser("x", "x", "", "x");
        users.put("admin", newUser("admin", "ADMIN", "", pw));
        load();
        if ("true".equalsIgnoreCase(env("EXPORT_DATA_FILE"))) {
            if (!useDb) throw new IOException("EXPORT_DATA_FILE requires a connected SQL database.");
            if (Files.exists(DB)) throw new IOException("Refusing to overwrite existing " + DB + "; set DATA_FILE to a new transfer filename.");
            saveFile();
            System.out.println("Exported everything to " + DB.toAbsolutePath());
            return;
        }
        if ("true".equalsIgnoreCase(env("IMPORT_DATA_FILE"))) importDataFile();
        migrateLegacyEvaluations();
        settings.computeIfAbsent("secret", k -> token() + token());   // signs the letter verification codes
        for (String d : new String[]{"static", ".", "PalmPortal/static", "src/static"})   // find the web files wherever they are
            if (Files.exists(Path.of(d, "index.html"))) { WEB = Path.of(d); break; }
        if (!Files.exists(WEB.resolve("index.html"))) System.out.println("WARNING: index.html not found. Put it in a 'static' folder next to Server.java.");
        String bind = env("BIND", "127.0.0.1"), fixed = env("PORT");
        HttpServer s = null;
        if (fixed != null) s = HttpServer.create(new InetSocketAddress(bind, Integer.parseInt(fixed)), 0);
        else for (int port = 8080; s == null && port < 8100; port++) {   // development: first free port from 8080
            try { s = HttpServer.create(new InetSocketAddress(bind, port), 0); } catch (BindException e) { /* port busy: try next */ }
        }
        if (s == null) throw new IOException("No free port between 8080 and 8099");
        s.createContext("/", Server::handle);
        s.setExecutor(Executors.newFixedThreadPool(16));
        s.start();
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(Server::housekeeping, 1, 24 * 60, TimeUnit.MINUTES);
        save();
        if (PRODUCTION) System.out.println("PRODUCTION mode. Serve this behind HTTPS (see DEPLOYMENT.md). Mail " + (mailOn() ? "is ON." : "is OFF: set SMTP_* so students can verify their email."));
        System.out.println("Open: http://localhost:" + s.getAddress().getPort());
    }

    // ---------- v7: production settings, audit log, notifications, email ----------
    static final boolean PRODUCTION = "true".equalsIgnoreCase(env("PRODUCTION"));
    static final boolean TRUST_PROXY = "true".equalsIgnoreCase(env("TRUST_PROXY"));   // set when running behind Nginx or Caddy
    static final Set<String> ORIGINS = new HashSet<>(Arrays.asList(env("ALLOWED_ORIGINS", "").split("\\s*,\\s*")));   // front-end origins allowed to call the API
    static final long SESSION_MS = 2 * 3_600_000L;   // sliding two-hour session
    static final ThreadLocal<String> WHO = ThreadLocal.withInitial(() -> "system");
    static final Map<String, String> settings = new ConcurrentHashMap<>();   // deadline, retentionDays, secret
    static final Map<String, String> register = Collections.synchronizedMap(new LinkedHashMap<>());   // allowed student emails -> name
    record AccessRequest(String id, String name, String program, String email, String reason, String date, String status) {}
    static final List<AccessRequest> accessRequests = new ArrayList<>();
    static final Set<String> unverified = ConcurrentHashMap.newKeySet();   // accounts waiting for email verification
    record Code(String code, long exp) {}
    static final Map<String, Code> verifyCodes = new ConcurrentHashMap<>();
    static final Map<String, long[]> rates = new ConcurrentHashMap<>();
    record Aud(String at, String actor, String action, String detail) {}
    record Note(String user, String at, String text, boolean read) {}
    record Eval(String sid, String cid, int punctuality, int skills, int attitude, int teamwork, String comment, String date) {}
    record EvaluationForm(String id, String title, String status, String createdOn) {}
    record EvaluationQuestion(String id, String formId, String prompt, String type, boolean required, int order, String options) {}
    record EvaluationResponse(String sid, String cid, String formId, String answers, String submittedOn) {}
    record LogEntry(String id, String sid, String cid, String week, String body, String status, String note, String date) {}
    static final List<Aud> audits = new ArrayList<>();
    static final List<Note> notes = new ArrayList<>();
    static final List<Eval> evals = new ArrayList<>();
    static final List<EvaluationForm> evaluationForms = new ArrayList<>();
    static final List<EvaluationQuestion> evaluationQuestions = new ArrayList<>();
    static final List<EvaluationResponse> evaluationResponses = new ArrayList<>();
    static final List<LogEntry> logs = new ArrayList<>();

    static synchronized void migrateLegacyEvaluations() {
        final String standardId = "EF_STANDARD";
        EvaluationForm standard = evaluationForms.stream().filter(f -> f.id().equals(standardId)).findFirst().orElse(null);
        if (standard == null) {
            standard = new EvaluationForm(standardId, "Standard evaluation", activeEvaluationForm() == null ? "ACTIVE" : "DRAFT", today());
            evaluationForms.add(standard);
            evaluationQuestions.addAll(List.of(
                new EvaluationQuestion("EQ_PUNCTUALITY", standardId, "Punctuality", "rating", true, 1, ""),
                new EvaluationQuestion("EQ_SKILLS", standardId, "Skills", "rating", true, 2, ""),
                new EvaluationQuestion("EQ_ATTITUDE", standardId, "Attitude", "rating", true, 3, ""),
                new EvaluationQuestion("EQ_TEAMWORK", standardId, "Teamwork", "rating", true, 4, ""),
                new EvaluationQuestion("EQ_COMMENT", standardId, "Additional comments", "long_text", false, 5, "")));
        }
        for (Eval old : evals) {
            boolean present = evaluationResponses.stream().anyMatch(r -> r.sid().equals(old.sid()) && r.cid().equals(old.cid()));
            if (!present) {
                String answers = "{\"EQ_PUNCTUALITY\":" + old.punctuality() + ",\"EQ_SKILLS\":" + old.skills()
                    + ",\"EQ_ATTITUDE\":" + old.attitude() + ",\"EQ_TEAMWORK\":" + old.teamwork()
                    + ",\"EQ_COMMENT\":" + q(old.comment()) + "}";
                evaluationResponses.add(new EvaluationResponse(old.sid(), old.cid(), standardId, answers, old.date() + "T12:00:00"));
            }
        }
        save();
    }
    static final Map<String, String> AI_CACHE = new ConcurrentHashMap<>();
    static final Semaphore AI_SLOTS = new Semaphore(3);   // at most three AI analyses at once

    static final String SMTP_HOST = env("SMTP_HOST"), SMTP_USER = env("SMTP_USER"), SMTP_PASS = env("SMTP_PASS"), SMTP_FROM = env("SMTP_FROM");
    static final int SMTP_PORT = Integer.parseInt(env("SMTP_PORT", "587"));
    static final String SMTP_SECURITY = env("SMTP_SECURITY", SMTP_PORT == 465 ? "ssl" : SMTP_PORT == 25 ? "none" : "starttls");
    static final ExecutorService MAILER = Executors.newSingleThreadExecutor();

    static String nid(String prefix, Stream<String> ids) {   // next free id such as S004 (never reuses a number while a higher one exists)
        int max = ids.mapToInt(i -> { try { return Integer.parseInt(i.substring(prefix.length())); } catch (Exception e) { return 0; } }).max().orElse(0);
        return String.format("%s%03d", prefix, max + 1);
    }
    static String setting(String k, String d) { String v = settings.get(k); return v == null || v.isBlank() ? d : v; }
    static boolean strong(String pw) { return pw.length() >= 8 && pw.length() <= 100 && pw.matches(".*[A-Za-z].*") && pw.matches(".*\\d.*"); }
    static final String WEAK = "Password must be 8 to 100 characters with at least one letter and one number";
    static String ip(HttpExchange x) {
        if (TRUST_PROXY) { String f = x.getRequestHeaders().getFirst("X-Forwarded-For"); if (f != null && !f.isBlank()) return f.split(",")[0].trim(); }
        return x.getRemoteAddress().getAddress().getHostAddress();
    }
    static boolean tooFast(String ip) {   // 300 API calls per minute per address
        long now = System.currentTimeMillis(); long[] r = rates.computeIfAbsent(ip, k -> new long[]{0, now});
        synchronized (r) { if (now - r[1] > 60_000) { r[0] = 0; r[1] = now; } return ++r[0] > 300; }
    }

    static synchronized void audit(String action, String detail) { audit(WHO.get(), action, detail); }
    static synchronized void audit(String actor, String action, String detail) { audits.add(new Aud(now(), actor, action, clean(detail, 300))); }

    static String userOf(String role, String ref) { return users.values().stream().filter(u -> u.role().equals(role) && u.ref().equals(ref)).map(User::name).findFirst().orElse(null); }
    static String emailOf(String username) {
        User u = users.get(username); if (u == null) return null;
        if (u.role().equals("COMPANY")) { Company c = company(u.ref()); return c != null && !c.email().isBlank() ? c.email() : null; }
        return username.contains("@") ? username : null;
    }
    static synchronized void tell(String username, String text) {   // in-app notification, plus an email when mail is configured
        if (username == null) return;
        notes.add(new Note(username, now(), clean(text, 300), false));
        String em = emailOf(username);
        if (em != null) sendMail(em, "Palm Internship Portal", text + "\n\nSign in to the portal to see the details.");
    }
    static void tellStudent(String sid, String text) { tell(userOf("STUDENT", sid), text); }
    static void tellCompany(String cid, String text) { tell(userOf("COMPANY", cid), text); }
    static void tellAdmins(String text) { users.values().stream().filter(u -> u.role().equals("ADMIN")).map(User::name).toList().forEach(n -> tell(n, text)); }

    static boolean mailOn() { return SMTP_HOST != null && !SMTP_HOST.isBlank() && SMTP_FROM != null && !SMTP_FROM.isBlank(); }
    static void sendMail(String to, String subject, String body) {   // sent in the background; a failure never blocks the portal
        if (!mailOn() || !ANY_EMAIL.matcher(to).matches()) return;
        MAILER.submit(() -> { try { smtp(to, subject, body); } catch (Exception e) { System.err.println("Mail to " + to + " failed: " + e.getMessage()); } });
    }
    static String smtpRead(BufferedReader in) throws IOException {
        String line, last = "";
        while ((line = in.readLine()) != null) { last = line; if (line.length() < 4 || line.charAt(3) != '-') break; }
        if (last.length() < 3 || last.charAt(0) >= '4') throw new IOException("SMTP error: " + last);
        return last;
    }
    static void smtpCmd(BufferedReader in, Writer out, String cmd) throws IOException { out.write(cmd + "\r\n"); out.flush(); smtpRead(in); }
    static void smtp(String to, String subject, String body) throws Exception {
        Socket sock = SMTP_SECURITY.equals("ssl") ? SSLSocketFactory.getDefault().createSocket(SMTP_HOST, SMTP_PORT) : new Socket(SMTP_HOST, SMTP_PORT);
        sock.setSoTimeout(15000);
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8);
            smtpRead(in);
            smtpCmd(in, out, "EHLO palm-portal");
            if (SMTP_SECURITY.equals("starttls")) {
                smtpCmd(in, out, "STARTTLS");
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(sock, SMTP_HOST, SMTP_PORT, true);
                ssl.startHandshake();
                in = new BufferedReader(new InputStreamReader(ssl.getInputStream(), StandardCharsets.UTF_8));
                out = new OutputStreamWriter(ssl.getOutputStream(), StandardCharsets.UTF_8);
                smtpCmd(in, out, "EHLO palm-portal");
            }
            if (SMTP_USER != null && !SMTP_USER.isBlank()) {
                smtpCmd(in, out, "AUTH LOGIN");
                smtpCmd(in, out, Base64.getEncoder().encodeToString(SMTP_USER.getBytes(StandardCharsets.UTF_8)));
                smtpCmd(in, out, Base64.getEncoder().encodeToString(SMTP_PASS.getBytes(StandardCharsets.UTF_8)));
            }
            smtpCmd(in, out, "MAIL FROM:<" + SMTP_FROM + ">");
            smtpCmd(in, out, "RCPT TO:<" + to + ">");
            smtpCmd(in, out, "DATA");
            out.write("From: Palm Internship Portal <" + SMTP_FROM + ">\r\nTo: <" + to + ">\r\nSubject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))
                + "?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder(76, "\r\n".getBytes()).encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "\r\n.\r\n");
            out.flush(); smtpRead(in);
            out.write("QUIT\r\n"); out.flush();
        } finally { sock.close(); }
    }

    static String hmac(String data) throws Exception {   // short signature used on approval letters
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(setting("secret", "x").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = m.doFinal(data.getBytes(StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(String.format("%02X", h[i]));
        return sb.substring(0, 4) + "-" + sb.substring(4, 8) + "-" + sb.substring(8, 12);
    }

    static void rm(Path p) { try { Files.deleteIfExists(p); } catch (IOException e) { /* nothing to delete */ } }
    static synchronized void erase(String sid) {   // removes a student and everything linked to them
        String un = userOf("STUDENT", sid);
        apps.removeIf(a -> { if (a.sid.equals(sid)) { rm(CVS.resolve(a.id + ".pdf")); return true; } return false; });
        placements.removeIf(p -> p.sid.equals(sid)); attendance.removeIf(w -> w.sid().equals(sid));
        logs.removeIf(l -> l.sid().equals(sid)); evals.removeIf(v -> v.sid().equals(sid));
        evaluationResponses.removeIf(v -> v.sid().equals(sid));
        reports.removeIf(k -> { if (k.sid().equals(sid)) { rm(REPORTS.resolve(sid + "_" + k.cid() + ".pdf")); return true; } return false; });
        rm(CVS.resolve(sid + ".pdf"));
        if (un != null) { users.remove(un); unverified.remove(un); notes.removeIf(n -> n.user().equals(un)); }
        students.removeIf(s -> s.id().equals(sid));
    }

    static void housekeeping() {   // runs daily: expired sessions and codes, old CV files, old read notifications
        try {
            long now = System.currentTimeMillis();
            sessions.values().removeIf(s -> s.exp() < now);
            resets.values().removeIf(r -> r.exp() < now); verifyCodes.values().removeIf(r -> r.exp() < now);
            rates.values().removeIf(r -> now - r[1] > 120_000);
            int days = 730;
            try { days = Integer.parseInt(setting("retentionDays", "730")); } catch (NumberFormatException e) { /* keep the default */ }
            java.time.Instant cutoff = java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
            if (Files.isDirectory(CVS)) try (var fs = Files.list(CVS)) { for (Path f : fs.toList()) if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) Files.deleteIfExists(f); }
            String cut = java.time.LocalDateTime.now().minusDays(90).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            synchronized (Server.class) {
                boolean locationPurged = false;
                java.time.LocalDateTime locationCutoff = java.time.LocalDateTime.now().minusDays(days);
                for (int i = 0; i < attendance.size(); i++) {
                    Att a = attendance.get(i);
                    if (java.time.LocalDateTime.parse(a.clockIn()).isBefore(locationCutoff)
                        && (a.inLat() != null || a.inLng() != null || a.inAccuracy() != null || a.outLat() != null || a.outLng() != null || a.outAccuracy() != null)) {
                        attendance.set(i, new Att(a.sid(), a.cid(), a.clockIn(), a.clockOut(), null, null, null, a.inDistance(),
                            a.inVerified(), null, null, null, a.outDistance()));
                        locationPurged = true;
                    }
                }
                if (locationPurged || notes.removeIf(n -> n.read() && n.at().compareTo(cut) < 0)) save();
            }
        } catch (Exception e) { System.err.println("Housekeeping failed: " + e.getMessage()); }
    }

    // ---------- security ----------
    static String token() { byte[] b = new byte[24]; RNG.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] hash(String pw, byte[] salt) throws Exception {   // PBKDF2-HMAC-SHA256, 120k rounds, per-user salt
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)).getEncoded();
    }
    static User newUser(String n, String role, String ref, String pw) throws Exception {
        return newUser(n, role, ref, pw, false);
    }
    static User newUser(String n, String role, String ref, String pw, boolean mustOnboard) throws Exception {
        return newUser(n, role, ref, pw, mustOnboard, !mustOnboard);
    }
    static User newUser(String n, String role, String ref, String pw, boolean mustOnboard, boolean passwordChanged) throws Exception {
        byte[] s = new byte[16]; RNG.nextBytes(s);
        return new User(n, role, ref, s, hash(pw, s), mustOnboard, passwordChanged);
    }
    static String clean(String s, int max) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }
    static Session auth(HttpExchange x) {   // per-tab bearer token; the session slides forward while the person is active
        String a = x.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return null;
        String tk = a.substring(7).trim(); Session s = sessions.get(tk);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (s.exp() <= now) { sessions.remove(tk); return null; }
        User current = users.getOrDefault(s.u().name(), s.u());
        Session fresh = new Session(s.csrf(), now + SESSION_MS, current); sessions.put(tk, fresh); return fresh;
    }

    static String ctype(String f) {
        return f.endsWith(".js") ? "text/javascript" : f.endsWith(".css") ? "text/css" : f.endsWith(".svg") ? "image/svg+xml"
             : f.endsWith(".png") ? "image/png" : f.endsWith(".jpg") || f.endsWith(".jpeg") ? "image/jpeg" : f.endsWith(".webp") ? "image/webp" : f.endsWith(".gif") ? "image/gif" : f.endsWith(".ico") ? "image/x-icon" : "text/html";
    }

    // ---------- HTTP plumbing ----------
    static void handle(HttpExchange x) throws IOException {
        try {
            Headers h = x.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff"); h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "no-referrer"); h.set("Cache-Control", "no-store");
            h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(self)"); h.set("Cross-Origin-Opener-Policy", "same-origin");
            if (PRODUCTION) h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data:; frame-src blob:; frame-ancestors 'none'; base-uri 'none'");
            String p = x.getRequestURI().getPath(), m = x.getRequestMethod();
            // CORS: ALLOWED_ORIGINS lists front-end addresses; in development any localhost page (for example Live Server) is also allowed
            String origin = x.getRequestHeaders().getFirst("Origin");
            if (origin != null && (ORIGINS.contains(origin) || (!PRODUCTION && origin.matches("http://(localhost|127\\.0\\.0\\.1)(:\\d+)?")))) {
                h.set("Access-Control-Allow-Origin", origin); h.set("Access-Control-Allow-Credentials", "true"); h.set("Vary", "Origin");
                h.set("Access-Control-Allow-Headers", "Content-Type, X-CSRF, Authorization"); h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            }
            if (m.equals("OPTIONS")) x.sendResponseHeaders(204, -1);
            else if (p.startsWith("/api/")) { if (tooFast(ip(x))) json(x, 429, err("Too many requests. Slow down for a moment.")); else api(x, p, m); }
            else if (m.equals("GET") && p.matches("/([A-Za-z0-9_-]+\\.(html|css|js|png|jpg|jpeg|svg|webp|gif|ico))?")) {
                String f = p.equals("/") ? "index.html" : p.substring(1);   // plain file names only: no folders, no path tricks
                Path file = WEB.resolve(f);
                if (!Files.exists(file)) json(x, 404, err("Not found"));
                else {
                    byte[] b = Files.readAllBytes(file);
                    String tag = "\"" + Long.toHexString(Files.getLastModifiedTime(file).toMillis()) + "-" + b.length + "\"";
                    h.set("ETag", tag); h.set("Cache-Control", "no-cache");   // browsers re-check, and get a tiny 304 when nothing changed
                    if (tag.equals(x.getRequestHeaders().getFirst("If-None-Match"))) x.sendResponseHeaders(304, -1); else send(x, 200, ctype(f), b);
                }
            } else json(x, 404, err("Not found"));
        } catch (Exception e) { e.printStackTrace(); json(x, 500, err("Server error")); }
        finally { x.close(); }
    }
    static void send(HttpExchange x, int code, String type, byte[] b) throws IOException {
        x.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        String ae = x.getRequestHeaders().getFirst("Accept-Encoding");
        if (b.length > 1024 && ae != null && ae.contains("gzip") && (type.startsWith("text/") || type.contains("json") || type.contains("svg"))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(b); }
            b = bo.toByteArray(); x.getResponseHeaders().set("Content-Encoding", "gzip"); x.getResponseHeaders().add("Vary", "Accept-Encoding");
        }
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }
    static void json(HttpExchange x, int c, String s) throws IOException { send(x, c, "application/json", s.getBytes(StandardCharsets.UTF_8)); }
    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    static String err(String m) { return "{\"error\":" + q(m) + "}"; }
    static String errCode(String code, String message) { return "{\"error\":" + q(message) + ",\"code\":" + q(code) + "}"; }
    static <T> String arr(Stream<T> s, Function<T, String> f) { return "[" + s.map(f).collect(Collectors.joining(",")) + "]"; }

    static Map<String, String> form(HttpExchange x) throws IOException {
        return form(x, 4096);
    }
    static Map<String, String> form(HttpExchange x, int max) throws IOException {
        return parse(new String(x.getRequestBody().readNBytes(max), StandardCharsets.UTF_8));
    }
    static Map<String, String> query(HttpExchange x) { String qs = x.getRequestURI().getRawQuery(); return parse(qs == null ? "" : qs); }
    static Map<String, String> parse(String body) {
        Map<String, String> m = new HashMap<>();
        for (String p : body.split("&")) {
            if (p.isEmpty()) continue;
            String[] kv = p.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8).trim() : "");
        }
        return m;
    }

    // ---------- routes ----------
    static void api(HttpExchange x, String p, String m) throws Exception {
        if (p.equals("/api/ping")) { json(x, 200, "{\"portal\":\"palm\",\"started\":" + STARTED + "}"); return; }   // lets the front end find this server
        if (p.equals("/api/login") && m.equals("POST")) { login(x); return; }
        if (m.equals("GET") && p.equals("/api/vl")) { verifyLetter(x); return; }   // anyone can check an approval letter
        if (m.equals("POST") && (p.equals("/api/signup") || p.equals("/api/forgot") || p.equals("/api/reset") || p.equals("/api/verify") || p.equals("/api/resend") || p.equals("/api/access-request"))) { publicPost(x, p); return; }
        Session s = auth(x);
        if (s == null) { json(x, 401, err("Please sign in")); return; }
        WHO.set(s.u().name());   // the audit log records who did what
        if (m.equals("POST") && !s.csrf().equals(x.getRequestHeaders().getFirst("X-CSRF"))) { json(x, 403, err("Invalid request token")); return; }
        boolean admin = s.u().role().equals("ADMIN");
        if (s.u().role().equals("COMPANY") && s.u().mustOnboard()
            && !(m.equals("GET") && p.equals("/api/data"))
            && !(m.equals("POST") && Set.of("/api/password", "/api/profile", "/api/company/location", "/api/onboarding/confirm", "/api/logout").contains(p))) {
            json(x, 403, errCode("ONBOARDING_REQUIRED", "Complete company onboarding before using the portal."));
            return;
        }
        if (!admin && ((m.equals("POST") && ADMIN_POST.contains(p)) || (m.equals("GET") && ADMIN_GET.contains(p)))) { json(x, 403, err("Not allowed for your role")); return; }
        if (m.equals("GET")) {
            switch (p) {
                case "/api/data" -> json(x, 200, data(s));
                case "/api/match" -> match(x);
                case "/api/export" -> export(x);
                case "/api/letter" -> letter(x, s);
                case "/api/cv" -> cv(x, s);
                case "/api/search" -> search(x, s);
                case "/api/report" -> report(x, s);
                case "/api/evaluation-forms" -> evaluationForms(x, s.u());
                case "/api/evaluation-report" -> evaluationReport(x, s.u());
                default -> json(x, 404, err("Not found"));
            }
            return;
        }
        if (!m.equals("POST")) { json(x, 405, err("Method not allowed")); return; }
        if (p.equals("/api/cv")) { uploadCv(x, s.u()); return; }
        if (p.equals("/api/analyse")) { analyse(x, s); return; }
        if (p.equals("/api/report")) { uploadReport(x, s.u()); return; }   // raw file body: must be read before form()
        Map<String, String> f = form(x, p.equals("/api/register") ? 524288
            : p.equals("/api/evaluation-submit") ? 262144 : p.equals("/api/evaluation-forms") ? 65536 : 4096);
        String r = switch (p) {
            case "/api/logout" -> { sessions.values().remove(s); yield OK; }
            case "/api/student" -> addStudent(f);
            case "/api/company" -> addCompany(f);
            case "/api/assign" -> assign(f);
            case "/api/status" -> status(f);
            case "/api/apply" -> apply(f, s.u());
            case "/api/review" -> review(f);
            case "/api/decide" -> decide(f, s.u());
            case "/api/password" -> changePassword(f, s);
            case "/api/profile" -> updateProfile(f, s.u());
            case "/api/company/location" -> setCompanyLocation(f, s.u());
            case "/api/onboarding/confirm" -> confirmCompanyOnboarding(s.u());
            case "/api/onboarding/reset" -> resetCompanyOnboarding(f);
            case "/api/evaluation-forms" -> saveEvaluationForm(f);
            case "/api/evaluation-forms/duplicate" -> duplicateEvaluationForm(f);
            case "/api/evaluation-forms/archive" -> archiveEvaluationForm(f);
            case "/api/evaluation-submit" -> submitEvaluation(f, s.u());
            case "/api/post" -> addPost(f, s.u());
            case "/api/message" -> messageStudent(f);
            case "/api/resetpw" -> adminReset(f);
            case "/api/clock" -> clock(f, s.u());
            case "/api/start" -> startIntern(f, s.u());
            case "/api/notesread" -> markRead(s.u());
            case "/api/staff" -> addStaff(f);
            case "/api/settings" -> saveSettings(f);
            case "/api/register" -> saveRegister(f);
            case "/api/access" -> decideAccess(f);
            case "/api/erase" -> eraseStudent(f);
            case "/api/deleteme" -> deleteMe(f, s.u());
            case "/api/withdraw" -> withdraw(f, s.u());
            case "/api/dates" -> setDates(f, s.u());
            case "/api/complete" -> complete(f, s.u());
            case "/api/log" -> addLog(f, s.u());
            case "/api/logreview" -> reviewLog(f, s.u());
            default -> null;
        };
        if (r == null) json(x, 404, err("Not found"));
        else json(x, r.startsWith("{\"error\"") ? 400 : 200, r);
    }

    static void login(HttpExchange x) throws Exception {
        String ip = ip(x);
        long[] f = fails.computeIfAbsent(ip, k -> new long[2]);
        long now = System.currentTimeMillis();
        if (now < f[1]) { json(x, 429, err("Too many attempts. Try again in a few minutes.")); return; }
        Map<String, String> in = form(x);
        String pw = in.getOrDefault("password", ""), un = in.getOrDefault("username", "").trim().toLowerCase();
        User u = users.get(un);
        User t = u != null ? u : DUMMY;   // same work for unknown users: no username enumeration
        boolean good = MessageDigest.isEqual(hash(pw.length() > 200 ? "" : pw, t.salt()), t.hash()) && u != null && pw.length() <= 200;
        if (!good) {
            if (++f[0] >= 5) { f[0] = 0; f[1] = now + 300_000; audit("system", "login_locked", "address " + ip); }
            audit(un.isEmpty() || un.length() > 80 ? "unknown" : un, "login_failed", "address " + ip);
            Thread.sleep(500);
            json(x, 401, err("Incorrect username or password")); return;
        }
        if (unverified.contains(un)) { json(x, 403, err("Please verify your email first: use \"Verify email\" on the sign-in page.")); return; }
        f[0] = 0;
        String sid = token(), csrf = token();
        sessions.put(sid, new Session(csrf, now + SESSION_MS, u));
        audit(un, "login", "address " + ip);
        json(x, 200, "{\"csrf\":" + q(csrf) + ",\"role\":" + q(u.role()) + ",\"token\":" + q(sid) + "}");
    }

    // ---------- lookups ----------
    static Student student(String id) { return students.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null); }
    static Company company(String id) { return companies.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null); }
    static App app(String id) { return apps.stream().filter(a -> a.id.equals(id)).findFirst().orElse(null); }
    static Placement active(String sid) { return placements.stream().filter(p -> p.sid.equals(sid) && !p.status.equals("CANCELLED")).findFirst().orElse(null); }
    static long filled(String cid) { return placements.stream().filter(p -> p.cid.equals(cid) && !p.status.equals("CANCELLED")).count(); }
    static boolean inProgress(String sid) { return apps.stream().anyMatch(a -> a.sid.equals(sid) && (a.status.equals("SUBMITTED") || a.status.equals("FORWARDED"))); }
    static String today() { return LocalDate.now().toString(); }
    static String cname(App a) { Company c = company(a.cid); return c != null ? c.name() : a.pref + " (not yet registered)"; }

    // ---------- admin: registration (also creates the login) ----------
    static synchronized String addStudent(Map<String, String> f) throws Exception {
        String n = clean(f.get("name"), 60), pr = clean(f.get("program"), 60);
        if (n.length() < 2 || pr.length() < 2) return err("Name and programme are required");
        String id = nid("S", students.stream().map(Student::id)), pw = tempPass();
        students.add(new Student(id, n, pr));
        users.put(id.toLowerCase(), newUser(id.toLowerCase(), "STUDENT", id, pw));
        save(); return "{\"ok\":true,\"login\":" + q(id.toLowerCase()) + ",\"password\":" + q(pw) + "}";
    }

    static synchronized String addCompany(Map<String, String> f) throws Exception {
        String n = clean(f.get("name"), 60), ind = clean(f.get("industry"), 40), foc = clean(f.get("focus"), 120);
        int cap;
        try { cap = Integer.parseInt(f.getOrDefault("capacity", "")); } catch (NumberFormatException e) { cap = 0; }
        if (n.length() < 2 || cap < 1 || cap > 500) return err("Name and a capacity of 1 to 500 are required");
        String em = clean(f.get("email"), 120);
        if (!em.isEmpty() && !ANY_EMAIL.matcher(em).matches()) return err("Contact email is not valid");
        String id = nid("C", companies.stream().map(Company::id)), pw = tempPass();
        companies.add(new Company(id, n, ind, foc, cap, em, "", ""));
        users.put(id.toLowerCase(), newUser(id.toLowerCase(), "COMPANY", id, pw, true, false)); audit("company_created", id + " " + n);
        save(); return "{\"ok\":true,\"login\":" + q(id.toLowerCase()) + ",\"password\":" + q(pw) + ",\"name\":" + q(n) + ",\"email\":" + q(em) + "}";
    }

    static synchronized String assign(Map<String, String> f) {   // direct assignment by the school
        Student s = student(f.get("sid")); Company c = company(f.get("cid"));
        if (s == null || c == null) return err("Unknown student or company");
        if (active(s.id()) != null) return err(s.name() + " already has a placement");
        if (filled(c.id()) >= c.capacity()) return err(c.name() + " is full");
        placements.add(new Placement(s.id(), c.id(), "PENDING", today()));
        App open = apps.stream().filter(a -> a.sid.equals(s.id()) && (a.status.equals("SUBMITTED") || a.status.equals("FORWARDED"))).findFirst().orElse(null);
        if (open == null) {   // record the assignment as an approved application, so the company, the student and the admin all see it
            open = new App(nid("A", apps.stream().map(z -> z.id)), s.id(), c.id(), "Placed directly by the school", "ACCEPTED", today());
            open.pref = c.name(); apps.add(open);
        } else { open.cid = c.id(); open.status = "ACCEPTED"; open.date = today(); }
        open.schoolNote = "You have been placed directly at " + c.name() + " by the school.";
        audit("assign", s.id() + " to " + c.id());
        tellCompany(c.id(), s.name() + " has been placed with you by the school.");
        tellStudent(s.id(), "You have been placed at " + c.name() + " by the school.");
        try {
            Path cvf = CVS.resolve(s.id() + ".pdf");
            if (Files.exists(cvf) && !Files.exists(CVS.resolve(open.id + ".pdf"))) Files.copy(cvf, CVS.resolve(open.id + ".pdf"));
        } catch (IOException e) { /* the placement still stands without a CV copy */ }
        save(); return OK;
    }

    static synchronized String status(Map<String, String> f) {
        Placement p = active(f.getOrDefault("sid", "")); String st = f.getOrDefault("status", "");
        if (p == null || !STATUSES.contains(st)) return err("Invalid update");
        p.status = st; if (st.equals("COMPLETED") && p.end.isEmpty()) p.end = today(); if (st.equals("ACTIVE") && p.start.isEmpty()) p.start = today();
        audit("placement_status", p.sid + " " + st); tellStudent(p.sid, "Your internship status is now " + st + "."); save(); return OK;
    }

    // ---------- application workflow ----------
    static synchronized String apply(Map<String, String> f, User u) throws Exception {   // STUDENT submits to the school
        if (!u.role().equals("STUDENT")) return err("Only students can apply");
        String dl = setting("deadline", "");
        if (!dl.isEmpty() && today().compareTo(dl) > 0) return err("Applications closed on " + dl + ".");
        String name = clean(f.get("company"), 60), stmt = clean(f.get("statement"), 400);
        if (name.length() < 2 || stmt.length() < 10) return err("Enter your preferred company and a short statement (10+ characters)");
        Company c = companies.stream().filter(k -> k.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (active(u.ref()) != null) return err("You already have a placement");
        if (inProgress(u.ref())) return err("You already have an application in progress");
        Path cv = CVS.resolve(u.ref() + ".pdf");
        if (!Files.exists(cv)) return err("Upload your CV (PDF) before applying");
        App a = new App(nid("A", apps.stream().map(z -> z.id)), u.ref(), c != null ? c.id() : "", stmt, "SUBMITTED", today());
        a.pref = name;
        Files.copy(cv, CVS.resolve(a.id + ".pdf"), StandardCopyOption.REPLACE_EXISTING);   // CV is frozen with the application
        apps.add(a);
        audit("apply", a.id + " to " + cname(a)); tellAdmins(student(u.ref()).name() + " submitted application " + a.id + ".");
        save(); return OK;
    }

    static synchronized String review(Map<String, String> f) {   // SCHOOL forwards to the company or rejects
        App a = app(f.get("app")); String act = f.getOrDefault("action", "");
        if (a == null || !a.status.equals("SUBMITTED")) return err("Application is not awaiting review");
        a.schoolNote = clean(f.get("note"), 300);   // message to the student
        if (act.equals("reject")) { a.status = "REJECTED"; audit("reject", a.id); tellStudent(a.sid, "Your application " + a.id + " was not approved by the school." + (a.schoolNote.isEmpty() ? "" : " Message: " + a.schoolNote)); }
        else if (act.equals("forward")) {
            String pick = f.get("cid");
            if (pick != null && company(pick) != null) a.cid = pick;   // school may choose or change the company
            Company c = company(a.cid);
            if (c == null) return err("Choose a registered company to forward to (register it first if needed)");
            if (filled(c.id()) >= c.capacity()) return err("That company has no open slots");
            a.status = "FORWARDED"; audit("forward", a.id + " to " + c.id());
            tellCompany(c.id(), "New application " + a.id + " from the school: please review the CV.");
            tellStudent(a.sid, "Your application " + a.id + " was approved by the school and sent to " + c.name() + "." + (a.schoolNote.isEmpty() ? "" : " Message: " + a.schoolNote));
        } else return err("Unknown action");
        a.date = today(); save(); return OK;
    }

    static synchronized String decide(Map<String, String> f, User u) {   // COMPANY accepts or declines
        App a = app(f.get("app")); String act = f.getOrDefault("action", "");
        if (!u.role().equals("COMPANY") || a == null || !a.cid.equals(u.ref()) || !a.status.equals("FORWARDED")) return err("Not allowed");
        a.companyNote = clean(f.get("note"), 300);   // feedback to the school
        if (act.equals("decline")) { a.status = "DECLINED"; audit("decline", a.id); tellStudent(a.sid, "Your application " + a.id + " was declined by the company."); tellAdmins(company(a.cid).name() + " declined " + student(a.sid).name() + "."); }
        else if (act.equals("accept")) {
            if (filled(a.cid) >= company(a.cid).capacity()) return err("You have no open slots left");
            if (active(a.sid) != null) return err("Student already placed elsewhere");
            placements.add(new Placement(a.sid, a.cid, "PENDING", today()));
            a.status = "ACCEPTED"; audit("accept", a.id);
            tellStudent(a.sid, "Congratulations! " + company(a.cid).name() + " accepted your application. Your approval letters are ready.");
            tellAdmins(company(a.cid).name() + " accepted " + student(a.sid).name() + ".");
        } else return err("Unknown action");
        a.date = today(); save(); return OK;
    }

    // ---------- views of the data, filtered by role ----------
    static synchronized String data(Session s) {
        User u = s.u(); String r = u.role();
        Stream<Student> ss = r.equals("ADMIN") ? students.stream() : Stream.empty();
        Stream<Company> cs = r.equals("COMPANY") ? companies.stream().filter(c -> c.id().equals(u.ref())) : companies.stream();
        Stream<Placement> ps = r.equals("ADMIN") ? placements.stream() : r.equals("COMPANY") ? placements.stream().filter(p -> p.cid.equals(u.ref())) : Stream.empty();
        Stream<App> as = apps.stream().filter(a -> r.equals("ADMIN") || (r.equals("STUDENT") ? a.sid.equals(u.ref()) : a.cid.equals(u.ref()) && COMPANY_SEES.contains(a.status)));
        List<Post> pl = new ArrayList<>(posts); Collections.reverse(pl);
        List<Att> al = new ArrayList<>(attendance); Collections.reverse(al);
        List<Rep> rl = new ArrayList<>(reports); Collections.reverse(rl);
        Stream<Map.Entry<String, Reset>> rs = r.equals("ADMIN") ? resets.entrySet().stream().filter(e -> e.getValue().exp() > System.currentTimeMillis() && !e.getValue().emailed()) : Stream.empty();
        String me = "{\"name\":" + q(r.equals("STUDENT") ? student(u.ref()).name() : r.equals("COMPANY") ? company(u.ref()).name() : (u.name().equals("admin") ? "Administrator" : u.name()))
                + ",\"placed\":" + (r.equals("STUDENT") && active(u.ref()) != null) + ",\"cv\":" + (r.equals("STUDENT") && Files.exists(CVS.resolve(u.ref() + ".pdf"))) + ",\"ai\":" + (!aiProvider().isEmpty()) + placementInfo(r, u)
                + ",\"unread\":" + notes.stream().filter(n -> n.user().equals(u.name()) && !n.read()).count()
                + ",\"mustOnboard\":" + u.mustOnboard() + ",\"passwordChanged\":" + u.passwordChanged() + "}";
        if (r.equals("COMPANY") && u.mustOnboard()) {
            Company c = company(u.ref());
            return "{\"role\":\"COMPANY\",\"me\":" + me + ",\"companies\":[" + companyJson(c, r)
                + "],\"settings\":{\"geofenceMeters\":" + q(setting("geofenceMeters", "100")) + "}}";
        }
        return "{\"role\":" + q(r) + ",\"me\":" + me
            + ",\"students\":" + arr(ss, t -> "{\"id\":" + q(t.id()) + ",\"name\":" + q(t.name()) + ",\"program\":" + q(t.program()) + ",\"email\":" + q(t.email()) + ",\"placed\":" + (active(t.id()) != null) + ",\"cv\":" + Files.exists(CVS.resolve(t.id() + ".pdf")) + "}")
            + ",\"companies\":" + arr(cs, t -> companyJson(t, r))
            + ",\"placements\":" + arr(ps, t -> "{\"sid\":" + q(t.sid) + ",\"student\":" + q(student(t.sid).name()) + ",\"studentEmail\":" + q(student(t.sid).email())
                + ",\"company\":" + q(company(t.cid).name()) + ",\"companyEmail\":" + q(company(t.cid).email()) + ",\"cid\":" + q(t.cid) + ",\"status\":" + q(t.status)
                + ",\"program\":" + q(student(t.sid).program()) + ",\"start\":" + q(t.start) + ",\"end\":" + q(t.end) + ",\"date\":" + q(t.date) + "}")
            + ",\"applications\":" + arr(as, a -> "{\"id\":" + q(a.id) + ",\"student\":" + q(student(a.sid).name()) + ",\"studentEmail\":" + q(student(a.sid).email())
                + ",\"company\":" + q(cname(a)) + ",\"companyEmail\":" + q(company(a.cid) == null ? "" : company(a.cid).email())
                + ",\"program\":" + q(student(a.sid).program()) + ",\"cid\":" + q(a.cid) + ",\"cv\":" + Files.exists(CVS.resolve(a.id + ".pdf")) + ",\"pstatus\":" + q(pstatus(a))
                + ",\"statement\":" + q(a.statement) + ",\"schoolNote\":" + q(r.equals("COMPANY") ? "" : a.schoolNote) + ",\"companyNote\":" + q(r.equals("STUDENT") ? "" : a.companyNote) + ",\"status\":" + q(a.status) + ",\"date\":" + q(a.date) + "}")
            + ",\"posts\":" + arr(pl.stream().filter(p -> !r.equals("COMPANY") || p.cid().equals(u.ref())), p -> "{\"id\":" + q(p.id()) + ",\"cid\":" + q(p.cid()) + ",\"company\":" + q(company(p.cid()).name()) + ",\"type\":" + q(p.type()) + ",\"title\":" + q(p.title()) + ",\"body\":" + q(p.body()) + ",\"date\":" + q(p.date()) + "}")
            + ",\"attendance\":" + arr(al.stream().filter(w -> r.equals("ADMIN") || (r.equals("STUDENT") ? w.sid().equals(u.ref()) : w.cid().equals(u.ref()))), w -> attendanceJson(w, r))
            + ",\"reports\":" + arr(rl.stream().filter(k -> r.equals("ADMIN") || (r.equals("COMPANY") && k.cid().equals(u.ref()))), k -> "{\"sid\":" + q(k.sid()) + ",\"student\":" + q(student(k.sid()).name())
                + ",\"studentEmail\":" + q(student(k.sid()).email()) + ",\"cid\":" + q(k.cid()) + ",\"company\":" + q(company(k.cid()).name())
                + ",\"companyEmail\":" + q(company(k.cid()).email()) + ",\"summary\":" + q(k.summary()) + ",\"date\":" + q(k.date()) + "}")
            + ",\"notes\":" + arr(notes.stream().filter(n -> n.user().equals(u.name())).sorted((x1, x2) -> x2.at().compareTo(x1.at())).limit(100), n -> "{\"at\":" + q(n.at()) + ",\"text\":" + q(n.text()) + ",\"read\":" + n.read() + "}")
            + ",\"logs\":" + arr(logs.stream().filter(l -> r.equals("ADMIN") || (r.equals("STUDENT") ? l.sid().equals(u.ref()) : l.cid().equals(u.ref()))).sorted((x1, x2) -> x2.id().compareTo(x1.id())), l -> "{\"id\":" + q(l.id()) + ",\"sid\":" + q(l.sid()) + ",\"student\":" + q(student(l.sid()).name()) + ",\"company\":" + q(company(l.cid()).name()) + ",\"week\":" + q(l.week()) + ",\"body\":" + q(l.body()) + ",\"status\":" + q(l.status()) + ",\"note\":" + q(l.note()) + ",\"date\":" + q(l.date()) + "}")
            + ",\"evals\":" + (r.equals("ADMIN")
                ? arr(evals.stream(), v -> "{\"sid\":" + q(v.sid()) + ",\"student\":" + q(student(v.sid()).name()) + ",\"studentEmail\":" + q(student(v.sid()).email())
                    + ",\"cid\":" + q(v.cid()) + ",\"company\":" + q(company(v.cid()).name()) + ",\"companyEmail\":" + q(company(v.cid()).email())
                    + ",\"punctuality\":" + v.punctuality() + ",\"skills\":" + v.skills() + ",\"attitude\":" + v.attitude() + ",\"teamwork\":" + v.teamwork() + ",\"comment\":" + q(v.comment()) + ",\"date\":" + q(v.date()) + "}")
                : r.equals("COMPANY") ? companyEvaluationStatuses(u.ref()) : "[]")
            + ",\"audit\":" + arr(r.equals("ADMIN") ? audits.stream().sorted((x1, x2) -> x2.at().compareTo(x1.at())).limit(300) : Stream.<Aud>empty(), a -> "{\"at\":" + q(a.at()) + ",\"actor\":" + q(a.actor()) + ",\"email\":" + q(auditEmail(a.actor())) + ",\"action\":" + q(a.action()) + ",\"detail\":" + q(a.detail()) + "}")
            + ",\"staff\":" + arr(r.equals("ADMIN") ? users.values().stream().filter(x1 -> x1.role().equals("ADMIN")).map(User::name).sorted() : Stream.<String>empty(), n -> q(n))
            + ",\"studentList\":" + arr(r.equals("ADMIN") ? register.entrySet().stream() : Stream.<Map.Entry<String, String>>empty(), e -> "{\"email\":" + q(e.getKey()) + ",\"name\":" + q(e.getValue()) + ",\"registered\":" + users.containsKey(e.getKey()) + "}")
            + ",\"accessRequests\":" + arr(r.equals("ADMIN") ? accessRequests.stream() : Stream.<AccessRequest>empty(), a -> "{\"id\":" + q(a.id()) + ",\"name\":" + q(a.name()) + ",\"program\":" + q(a.program()) + ",\"email\":" + q(a.email()) + ",\"reason\":" + q(a.reason()) + ",\"date\":" + q(a.date()) + ",\"status\":" + q(a.status()) + "}")
            + ",\"settings\":{\"deadline\":" + q(setting("deadline", "")) + ",\"retentionDays\":" + q(setting("retentionDays", "730"))
            + ",\"geofenceMeters\":" + q(setting("geofenceMeters", "100")) + ",\"registerCount\":" + register.size() + ",\"mail\":" + mailOn() + ",\"production\":" + PRODUCTION + "}"
            + ",\"resets\":" + arr(rs, e -> "{\"username\":" + q(e.getValue().username()) + ",\"code\":" + q(e.getKey()) + ",\"minutes\":" + Math.max(0, (e.getValue().exp() - System.currentTimeMillis()) / 60000) + "}") + "}";
    }

    static String companyJson(Company c, String role) {
        String extra = "";
        if (role.equals("ADMIN")) {
            String username = userOf("COMPANY", c.id());
            User account = username == null ? null : users.get(username);
            extra = ",\"mustOnboard\":" + (account != null && account.mustOnboard())
                + ",\"passwordChanged\":" + (account != null && account.passwordChanged())
                + ",\"profileComplete\":" + profileComplete(c)
                + ",\"locationSetOn\":" + q(c.locationSetOn());
        } else if (role.equals("COMPANY")) {
            extra = ",\"profileComplete\":" + profileComplete(c) + ",\"profileDetailsComplete\":" + profileDetailsComplete(c);
        }
        return "{\"id\":" + q(c.id()) + ",\"name\":" + q(c.name()) + ",\"industry\":" + q(c.industry())
            + ",\"focus\":" + q(c.focus()) + ",\"about\":" + q(c.about()) + ",\"website\":" + q(c.website())
            + ",\"email\":" + q(role.equals("STUDENT") ? "" : c.email()) + ",\"capacity\":" + c.capacity()
            + ",\"filled\":" + filled(c.id()) + ",\"locationLat\":" + (c.locationLat() == null ? "null" : c.locationLat())
            + ",\"locationLng\":" + (c.locationLng() == null ? "null" : c.locationLng())
            + ",\"locationAddress\":" + q(c.locationAddress()) + ",\"mapsLink\":" + q(c.mapsLink()) + extra + "}";
    }

    static String attendanceJson(Att a, String role) {
        String out = "{\"sid\":" + q(a.sid()) + ",\"student\":" + q(student(a.sid()).name()) + ",\"cid\":" + q(a.cid())
            + ",\"studentEmail\":" + q(student(a.sid()).email()) + ",\"company\":" + q(company(a.cid()).name())
            + ",\"companyEmail\":" + q(company(a.cid()).email()) + ",\"in\":" + q(a.clockIn()) + ",\"out\":" + q(a.clockOut())
            + ",\"hours\":" + q(hours(a)) + ",\"locationVerified\":" + a.inVerified()
            + ",\"locationStatus\":" + q(a.inVerified() ? "Verified on site" : "Not verified");
        if (role.equals("ADMIN") || role.equals("STUDENT")) {
            out += ",\"inLat\":" + jsonNumber(a.inLat()) + ",\"inLng\":" + jsonNumber(a.inLng())
                + ",\"inAccuracy\":" + jsonNumber(a.inAccuracy()) + ",\"inDistance\":" + jsonNumber(a.inDistance())
                + ",\"outLat\":" + jsonNumber(a.outLat()) + ",\"outLng\":" + jsonNumber(a.outLng())
                + ",\"outAccuracy\":" + jsonNumber(a.outAccuracy()) + ",\"outDistance\":" + jsonNumber(a.outDistance());
        }
        return out + "}";
    }

    static String jsonNumber(Double value) { return value == null ? "null" : Double.toString(value); }

    static String companyEvaluationStatuses(String cid) {
        return arr(placements.stream().filter(placement -> placement.cid.equals(cid) && !placement.status.equals("CANCELLED")), placement -> {
            boolean submitted = evaluationResponses.stream().anyMatch(response -> response.sid().equals(placement.sid) && response.cid().equals(cid));
            Student intern = student(placement.sid);
            return "{\"sid\":" + q(placement.sid) + ",\"student\":" + q(intern == null ? "" : intern.name())
                + ",\"cid\":" + q(cid) + ",\"company\":" + q(company(cid).name()) + ",\"submitted\":" + submitted
                + ",\"status\":" + q(submitted ? "Submitted" : "Pending") + "}";
        });
    }

    // ---------- letters of approval (only once the company has accepted) ----------
    static synchronized void letter(HttpExchange x, Session s) throws IOException {
        Map<String, String> qp = query(x); App a = app(qp.get("app")); User u = s.u();
        boolean mine = a != null && (u.role().equals("ADMIN") || (u.role().equals("STUDENT") && a.sid.equals(u.ref())) || (u.role().equals("COMPANY") && a.cid.equals(u.ref())));
        if (!mine) { json(x, 404, err("Not found")); return; }
        if (!a.status.equals("ACCEPTED")) { json(x, 400, err("No letter yet: the application is not approved")); return; }
        Student st = student(a.sid); Company c = company(a.cid);
        boolean school = "school".equals(qp.get("type"));
        String body = (school ? "PALM UNIVERSITY\nInternship Placement Office" : c.name().toUpperCase() + "\nInternship Programme")
            + "\n\nRef: " + (school ? "PU/INT/" : "CO/INT/") + a.id + "\nDate: " + a.date + "\n\nDear " + st.name() + ",\n\n"
            + (school
                ? "The Internship Placement Office of Palm University confirms that your application for an internship placement (" + st.program() + ") has been reviewed and approved by the University, and that " + c.name() + " has accepted you as an intern.\n\nYou are expected to represent Palm University with integrity, observe the host organisation's rules and keep the Office informed of your progress.\n\nWe wish you a rewarding internship.\n\nYours faithfully,\nInternship Placement Office\nPalm University"
                : "We are pleased to confirm that " + c.name() + " has approved your application and offers you an internship placement under the Palm University internship programme.\n\nYour supervisor will contact you with your start date and reporting instructions. We look forward to working with you.\n\nSincerely,\nInternship Coordinator\n" + c.name());
        json(x, 200, "{\"title\":" + q(school ? "Letter of Approval: Palm University" : "Letter of Approval: " + c.name()) + ",\"body\":" + q(body) + ",\"ref\":" + q(a.id) + ",\"code\":" + q(letterCode(a)) + "}");
    }

    // ---------- CV upload (PDF only, max 2 MB, stored outside the web folder) ----------
    static void uploadCv(HttpExchange x, User u) throws IOException {
        if (!u.role().equals("STUDENT")) { json(x, 403, err("Only students can upload a CV")); return; }
        byte[] b = x.getRequestBody().readNBytes(2_097_153);
        if (b.length > 2_097_152) { json(x, 413, err("CV must be 2 MB or smaller")); return; }
        if (b.length < 5 || !new String(b, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-")) { json(x, 400, err("Please upload a PDF file")); return; }
        Files.createDirectories(CVS);
        Files.write(CVS.resolve(u.ref() + ".pdf"), b); audit(u.name(), "cv_uploaded", u.ref());   // filename is server-generated: no path tricks
        json(x, 200, OK);
    }

    static synchronized void cv(HttpExchange x, Session s) throws IOException {
        Map<String, String> qp = query(x); User u = s.u(); Path f = null;
        if (qp.get("sid") != null) {   // the admin can open any student's current CV, even before they apply
            if (u.role().equals("ADMIN") && student(qp.get("sid")) != null) f = CVS.resolve(qp.get("sid") + ".pdf");
        } else {
            App a = app(qp.get("app"));
            boolean ok = a != null && (u.role().equals("ADMIN") || (u.role().equals("STUDENT") && a.sid.equals(u.ref()))
                    || (u.role().equals("COMPANY") && a.cid.equals(u.ref()) && COMPANY_SEES.contains(a.status)));
            if (ok) f = CVS.resolve(a.id + ".pdf");
        }
        if (f == null || !Files.exists(f)) { json(x, 404, err("CV not found")); return; }
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"CV.pdf\"");
        send(x, 200, "application/pdf", Files.readAllBytes(f));
    }

    // ---------- v5: sign-up, password reset, profiles, posts, web search, AI match ----------
    record Reset(String username, long exp, boolean emailed) {}
    record Post(String id, String cid, String type, String title, String body, String date) {}
    static boolean allowedEmail(String e) {
        return ANY_EMAIL.matcher(e).matches() && !register.isEmpty() && register.containsKey(e);
    }
    static final Pattern ANY_EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    static final Map<String, Reset> resets = new ConcurrentHashMap<>();
    static final List<Post> posts = new ArrayList<>();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String SEARCH_KEY = env("SEARCH_KEY"), SEARCH_CX = env("SEARCH_CX");   // Google Programmable Search (optional)
    static final String AI_KEY = env("ANTHROPIC_API_KEY");                                       // AI CV analysis (optional)
    static final String AI_MODEL = env("ANTHROPIC_MODEL", "claude-sonnet-5-5");
    static final String GEMINI_KEY = env("GEMINI_API_KEY");                                       // free AI option (Google AI Studio key)
    static final String GEMINI_MODEL = env("GEMINI_MODEL", "gemini-2.5-flash");

    static boolean throttled(String key, int max) {
        long now = System.currentTimeMillis();
        long[] f = fails.computeIfAbsent(key, k -> new long[]{0, now});
        synchronized (f) { if (now - f[1] > 600_000) { f[0] = 0; f[1] = now; } return ++f[0] > max; }
    }

    static void publicPost(HttpExchange x, String p) throws Exception {   // no sign-in needed: sign-up and password reset
        String ip = ip(x);
        if (throttled("p:" + ip, 30)) { json(x, 429, err("Too many requests. Try again later.")); return; }
        Map<String, String> f = form(x);
        String r = switch (p) { case "/api/signup" -> signup(f); case "/api/forgot" -> forgot(f); case "/api/verify" -> verifyEmail(f, ip); case "/api/resend" -> resend(f, ip); case "/api/access-request" -> accessRequest(f, ip); default -> reset(f, ip); };
        json(x, r.startsWith("{\"error\"") ? 400 : 200, r);
    }

    static synchronized String signup(Map<String, String> f) throws Exception {
        String name = clean(f.get("name"), 60), prog = clean(f.get("program"), 60), email = clean(f.get("email"), 120).toLowerCase(), pw = f.getOrDefault("password", "");
        if (!"1".equals(f.get("consent"))) return err("Please accept the privacy notice to create an account");
        if (name.length() < 2 || prog.length() < 2) return err("Name and programme are required");
        if (register.isEmpty()) return err("Student registration is currently closed. Please contact the Internship Office.");
        if (!allowedEmail(email)) return err("We could not verify your eligibility. If you are an eligible student, use Request Access to contact the Internship Office.");
        if (PRODUCTION && !mailOn()) return err("Email verification is temporarily unavailable. Please contact the Internship Office.");
        if (!strong(pw)) return err(WEAK);
        if (users.containsKey(email)) return err("An account with this email already exists");
        String approvedName = register.get(email);
        if (approvedName != null && !approvedName.isBlank()) name = approvedName;
        String id = nid("S", students.stream().map(Student::id));
        students.add(new Student(id, name, prog, email));
        users.put(email, newUser(email, "STUDENT", id, pw));
        boolean verify = mailOn();   // email verification needs mail to be configured
        if (verify) { unverified.add(email); sendCode(email); }
        audit(email, "signup", id + (verify ? " (waiting for email verification)" : ""));
        audit(email, "consent", "privacy notice accepted");
        save(); return "{\"ok\":true,\"verify\":" + verify + "}";
    }

    static synchronized String accessRequest(Map<String, String> f, String ip) {
        if (throttled("ar:" + ip, 5)) return err("Too many access requests. Try again later.");
        String name = clean(f.get("name"), 60), program = clean(f.get("program"), 60);
        String email = clean(f.get("email"), 120).toLowerCase(), reason = clean(f.get("reason"), 300);
        if (name.length() < 2 || program.length() < 2 || !ANY_EMAIL.matcher(email).matches())
            return err("Please provide your name, programme and a valid email address.");
        if (register.containsKey(email) || users.containsKey(email)
                || accessRequests.stream().anyMatch(r -> r.email().equals(email) && r.status().equals("PENDING"))) return OK;
        String id = nid("AR", accessRequests.stream().map(AccessRequest::id));
        accessRequests.add(new AccessRequest(id, name, program, email, reason, now(), "PENDING"));
        audit("student_access_requested", id + " " + email);
        tellAdmins("Student access request received for review: " + name + " (" + email + ").");
        save();
        return OK;
    }

    static synchronized String decideAccess(Map<String, String> f) {
        String id = f.getOrDefault("id", ""), decision = f.getOrDefault("decision", "").toLowerCase();
        AccessRequest request = accessRequests.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
        if (request == null) return err("Access request not found");
        if (!decision.equals("approve") && !decision.equals("reject")) return err("Unknown access decision");
        if (decision.equals("approve")) {
            register.put(request.email(), request.name());
            audit("student_access_approved", id + " " + request.email());
            sendMail(request.email(), "Palm Internship Portal access approved",
                    "Your student email has been added to the approved internship student list. You may now create your portal account.");
        } else {
            audit("student_access_rejected", id + " " + request.email());
        }
        accessRequests.removeIf(r -> r.id().equals(id));
        save();
        return OK;
    }

    static void sendCode(String email) {
        String code = "";
        for (int i = 0; i < 6; i++) code += RNG.nextInt(10);
        verifyCodes.put(email, new Code(code, System.currentTimeMillis() + 1_800_000));
        sendMail(email, "Verify your email", "Your Palm Internship Portal verification code is " + code + ". It expires in 30 minutes.");
    }

    static synchronized String verifyEmail(Map<String, String> f, String ip) {
        if (throttled("v:" + ip, 10)) return err("Too many attempts. Try again later.");
        String em = f.getOrDefault("username", "").trim().toLowerCase(), code = f.getOrDefault("code", "").trim();
        Code c = verifyCodes.get(em);
        if (c == null || c.exp() < System.currentTimeMillis() || !c.code().equals(code)) return err("Invalid or expired code");
        verifyCodes.remove(em); unverified.remove(em); audit(em, "email_verified", ""); save(); return OK;
    }

    static synchronized String resend(Map<String, String> f, String ip) {
        if (throttled("rv:" + ip, 5)) return err("Too many requests. Try again later.");
        String em = f.getOrDefault("username", "").trim().toLowerCase();
        if (unverified.contains(em)) sendCode(em);
        return OK;
    }

    static synchronized String forgot(Map<String, String> f) {   // always answers OK: no account enumeration
        String name = f.getOrDefault("username", "").trim().toLowerCase();
        User u = users.get(name);
        if (u != null && !name.equals("admin")) {
            resets.values().removeIf(r -> r.username().equals(name));
            String abc = "ABCDEFGHJKMNPQRSTUVWXYZ23456789", code = "";
            for (int i = 0; i < 8; i++) code += abc.charAt(RNG.nextInt(abc.length()));
            String em = emailOf(name);
            boolean emailed = mailOn() && em != null;
            resets.put(code, new Reset(name, System.currentTimeMillis() + 1_800_000, emailed));   // valid for 30 minutes
            if (emailed) sendMail(em, "Reset your password", "Your Palm Internship Portal reset code is " + code + ". It expires in 30 minutes. If you did not ask for it, ignore this email.");
            else System.out.println("PASSWORD RESET CODE for " + name + ": " + code);   // no email possible: the school office hands it over
            audit(name, "password_reset_requested", emailed ? "code emailed" : "code held for the school office");
        }
        return OK;
    }

    static synchronized String reset(Map<String, String> f, String ip) throws Exception {
        if (throttled("r:" + ip, 10)) return err("Too many attempts. Try again later.");
        String name = f.getOrDefault("username", "").toLowerCase(), pw = f.getOrDefault("password", ""), code = f.getOrDefault("code", "").toUpperCase();
        Reset r = resets.get(code);
        if (r == null || r.exp() < System.currentTimeMillis() || !r.username().equals(name)) return err("Invalid or expired code");
        if (!strong(pw)) return err(WEAK);
        User u = users.get(name);
        boolean onboarding = u.role().equals("COMPANY");
        users.put(name, newUser(name, u.role(), u.ref(), pw, onboarding, !onboarding));
        resets.remove(code); audit(name, "password_reset", "");
        sessions.values().removeIf(s -> s.u().name().equals(name));   // sign out everywhere
        save(); return OK;
    }

    static synchronized String changePassword(Map<String, String> f, Session s) throws Exception {
        User u = s.u(); String pw = f.getOrDefault("password", "");
        if (u.name().equals("admin")) return err("The main admin password is set when the server starts (ADMIN_PASSWORD)");
        User cur = users.get(u.name());
        if (!MessageDigest.isEqual(hash(f.getOrDefault("old", ""), cur.salt()), cur.hash())) return err("Current password is incorrect");
        if (!strong(pw)) return err(WEAK);
        if (cur.role().equals("COMPANY") && cur.mustOnboard()
            && MessageDigest.isEqual(hash(pw, cur.salt()), cur.hash())) return err("Choose a new password instead of reusing the temporary password.");
        users.put(u.name(), newUser(u.name(), u.role(), u.ref(), pw, cur.mustOnboard(), true)); audit("password_changed", "");
        sessions.values().removeIf(t -> t != s && t.u().name().equals(u.name()));
        save(); return OK;
    }

    static synchronized String updateProfile(Map<String, String> f, User u) {
        if (!u.role().equals("COMPANY")) return err("Companies only");
        int i = -1;
        for (int k = 0; k < companies.size(); k++) if (companies.get(k).id().equals(u.ref())) i = k;
        if (i < 0) return err("Company not found");
        Company o = companies.get(i);
        String name = clean(f.get("name"), 60), web = clean(f.get("website"), 200), em = clean(f.get("email"), 120);
        if (name.length() < 2) return err("Company name is required");
        if (!web.isEmpty() && !web.matches("^https?://[^\\s]{3,}$")) return err("Website must start with http:// or https://");
        if (!em.isEmpty() && !ANY_EMAIL.matcher(em).matches()) return err("Contact email is not valid");
        companies.set(i, new Company(o.id(), name, clean(f.get("industry"), 40), clean(f.get("focus"), 120), o.capacity(), em, web,
            clean(f.get("about"), 600), o.locationLat(), o.locationLng(), o.locationAddress(), o.mapsLink(), o.locationSetOn(), o.locationSetBy()));
        audit("company_profile_updated", o.id());
        save(); return OK;
    }

    record Coordinates(double lat, double lng) {}
    static Coordinates parseCoordinates(String text) {
        Matcher m = Pattern.compile("([-+]?(?:\\d{1,2}(?:\\.\\d+)?|90(?:\\.0+)?))\\s*,\\s*([-+]?(?:\\d{1,3}(?:\\.\\d+)?|180(?:\\.0+)?))").matcher(text);
        if (!m.find()) return null;
        try {
            double lat = Double.parseDouble(m.group(1)), lng = Double.parseDouble(m.group(2));
            return Double.isFinite(lat) && Double.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                ? new Coordinates(lat, lng) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static synchronized String setCompanyLocation(Map<String, String> f, User u) {
        String cid = u.role().equals("COMPANY") ? u.ref() : f.getOrDefault("cid", "");
        if (!(u.role().equals("COMPANY") || u.role().equals("ADMIN"))) return err("Companies or administrators only");
        int index = -1;
        for (int i = 0; i < companies.size(); i++) if (companies.get(i).id().equals(cid)) index = i;
        if (index < 0) return err("Company not found");
        Company old = companies.get(index);
        Coordinates point = null;
        String latText = f.getOrDefault("lat", "").trim(), lngText = f.getOrDefault("lng", "").trim();
        if (!latText.isEmpty() || !lngText.isEmpty()) {
            if (latText.isEmpty() || lngText.isEmpty()) return err("Enter both latitude and longitude.");
            point = parseCoordinates(latText + "," + lngText);
        } else {
            point = parseCoordinates(f.getOrDefault("location", "").trim());
        }
        if (point == null) return err("Enter valid coordinates or a Google Maps link with coordinates.");
        String address = clean(f.get("address"), 300);
        if (address.isEmpty()) address = old.locationAddress();
        if (address.length() < 3) return err("Enter the workplace address.");
        String link = "https://www.google.com/maps?q=" + point.lat() + "," + point.lng();
        companies.set(index, new Company(old.id(), old.name(), old.industry(), old.focus(), old.capacity(), old.email(), old.website(), old.about(),
            point.lat(), point.lng(), address, link, now(), u.name()));
        audit("company_location_updated", cid);
        if (u.role().equals("COMPANY")) tellAdmins(company(cid).name() + " updated its workplace location.");
        save();
        return "{\"ok\":true,\"lat\":" + point.lat() + ",\"lng\":" + point.lng() + ",\"mapsLink\":" + q(link) + "}";
    }

    static boolean profileComplete(Company c) {
        return profileDetailsComplete(c) && c.locationAddress().length() >= 3;
    }

    static boolean profileDetailsComplete(Company c) {
        return c != null && c.industry().length() >= 2 && c.about().length() >= 10
            && ANY_EMAIL.matcher(c.email()).matches();
    }

    static synchronized String confirmCompanyOnboarding(User u) {
        if (!u.role().equals("COMPANY")) return err("Companies only");
        User current = users.get(u.name());
        Company c = company(u.ref());
        if (current == null || c == null) return err("Company account not found");
        if (!current.passwordChanged()) return err("Change the temporary password before continuing.");
        if (!profileComplete(c)) return err("Complete the industry, about, contact email, and workplace address first.");
        if (c.locationLat() == null || c.locationLng() == null) return err("Set the workplace location before continuing.");
        users.put(current.name(), new User(current.name(), current.role(), current.ref(), current.salt(), current.hash(), false, true));
        audit("company_onboarding_completed", current.ref());
        tellAdmins(c.name() + " completed company onboarding.");
        save();
        return OK;
    }

    static synchronized String resetCompanyOnboarding(Map<String, String> f) throws Exception {
        String cid = f.getOrDefault("cid", "");
        Company c = company(cid);
        String name = userOf("COMPANY", cid);
        User current = name == null ? null : users.get(name);
        if (c == null || current == null) return err("Company account not found.");
        String password = tempPass();
        users.put(name, newUser(name, "COMPANY", cid, password, true, false));
        sessions.values().removeIf(session -> session.u().name().equals(name));
        audit("company_onboarding_reset", cid);
        tellCompany(cid, "Your administrator reset onboarding. Sign in with the new temporary password.");
        save();
        return "{\"ok\":true,\"login\":" + q(name) + ",\"password\":" + q(password) + ",\"name\":" + q(c.name()) + ",\"email\":" + q(c.email()) + "}";
    }

    static synchronized String addPost(Map<String, String> f, User u) {
        if (!u.role().equals("COMPANY")) return err("Companies only");
        String type = f.getOrDefault("type", ""), title = clean(f.get("title"), 80), body = clean(f.get("body"), 600);
        if (!type.equals("AD") && !type.equals("UPDATE")) return err("Unknown post type");
        if (title.length() < 3 || body.length() < 10) return err("Add a title and some details (10+ characters)");
        posts.add(new Post(nid("P", posts.stream().map(Post::id)), u.ref(), type, title, body, today()));
        save(); return OK;
    }

    // ----- search: portal companies, plus live web results when SEARCH_KEY and SEARCH_CX are set -----
    static String unjson(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n', 't' -> b.append(' ');
                case 'r' -> {}
                case 'u' -> { if (i + 4 < s.length()) { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } }
                default -> b.append(n);
            }
        }
        return b.toString();
    }

    static String webSearch(String term) throws Exception {
        String url = "https://www.googleapis.com/customsearch/v1?num=6&key=" + URLEncoder.encode(SEARCH_KEY, StandardCharsets.UTF_8)
                + "&cx=" + URLEncoder.encode(SEARCH_CX, StandardCharsets.UTF_8) + "&q=" + URLEncoder.encode(term, StandardCharsets.UTF_8);
        String body = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString()).body();
        int i = body.indexOf("\"items\"");
        if (i < 0) return "[]";
        Matcher m = Pattern.compile("\"(title|link|snippet)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body.substring(i));
        List<String> out = new ArrayList<>(); String t = "", l = "";
        while (m.find()) {
            String v = unjson(m.group(2));
            switch (m.group(1)) {
                case "title" -> t = v;
                case "link" -> l = v;
                default -> { if (l.startsWith("http")) out.add("{\"title\":" + q(t) + ",\"link\":" + q(l) + ",\"snippet\":" + q(v) + "}"); }
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    static void search(HttpExchange x, Session s) throws Exception {
        if (s.u().role().equals("COMPANY")) { json(x, 403, err("Not allowed for your role")); return; }
        String term = clean(query(x).get("q"), 80), low = term.toLowerCase(), portal;
        if (term.length() < 2) { json(x, 400, err("Type at least 2 characters")); return; }
        synchronized (Server.class) {
            portal = arr(companies.stream().filter(c -> (c.name() + " " + c.industry() + " " + c.focus() + " " + c.about()).toLowerCase().contains(low)),
                c -> "{\"id\":" + q(c.id()) + ",\"name\":" + q(c.name()) + ",\"industry\":" + q(c.industry()) + ",\"about\":" + q(c.about()) + ",\"website\":" + q(c.website()) + "}");
        }
        boolean on = SEARCH_KEY != null && !SEARCH_KEY.isBlank() && SEARCH_CX != null && !SEARCH_CX.isBlank();   // blank = off
        String web = "[]";
        if (on) { try { web = webSearch(term); } catch (Exception e) { System.err.println("Web search failed: " + e.getMessage()); } }
        json(x, 200, "{\"portal\":" + portal + ",\"web\":" + web + ",\"webEnabled\":" + on + ",\"link\":" + q("https://www.google.com/search?q=" + URLEncoder.encode(term + " company", StandardCharsets.UTF_8)) + "}");
    }

    // ----- AI analysis framework: two interchangeable matchers behind one endpoint -----
    static String pdfText(Path f) {   // best-effort text from simple PDFs; scanned or unusual PDFs return little
        StringBuilder out = new StringBuilder();
        try {
            byte[] b = Files.readAllBytes(f);
            Matcher sm = Pattern.compile("stream\\r?\\n").matcher(new String(b, StandardCharsets.ISO_8859_1));
            while (sm.find()) {
                int st = sm.end(), en = new String(b, StandardCharsets.ISO_8859_1).indexOf("endstream", st);
                if (en < 0) break;
                try {
                    Inflater inf = new Inflater(); inf.setInput(b, st, en - st);
                    byte[] buf = new byte[1 << 16]; int n = inf.inflate(buf); inf.end();
                    Matcher t = Pattern.compile("\\(((?:[^()\\\\]|\\\\.)*)\\)").matcher(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                    while (t.find()) out.append(t.group(1)).append(' ');
                } catch (Exception skip) { /* not a text stream */ }
            }
        } catch (Exception e) { /* unreadable */ }
        return out.toString();
    }

    static List<String[]> localMatch(Student st, String cvText, List<Company> cs) {   // built-in keyword matcher
        Set<String> want = words(st.program() + " " + cvText);
        List<String[]> out = new ArrayList<>();
        for (Company c : cs) {
            Set<String> have = words(c.industry() + " " + c.focus() + " " + c.about() + " " + c.name()); have.retainAll(want);
            long free = c.capacity() - filled(c.id());
            int score = have.isEmpty() ? 10 : (int) Math.min(99, 25 + 12L * have.size() + Math.min(free, 5));
            out.add(new String[]{c.id(), "" + score, have.isEmpty() ? "No shared keywords yet. The company may need a fuller profile." : "Shared keywords: " + String.join(", ", have.stream().limit(6).toList())});
        }
        out.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        return out.subList(0, Math.min(5, out.size()));
    }

    static List<String[]> claudeMatch(Student st, byte[] pdf, List<Company> cs, String skills, boolean web) throws Exception {   // reads the real PDF and may search the web
        String prompt = matchPrompt(st, cs, skills);
        String body = "{\"model\":" + q(AI_MODEL) + ",\"max_tokens\":1500," + (web ? "\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":3}]," : "")
            + "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"" + Base64.getEncoder().encodeToString(pdf) + "\"}},{\"type\":\"text\",\"text\":" + q(prompt) + "}]}]}";
        var req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages")).timeout(Duration.ofSeconds(90))
            .header("x-api-key", AI_KEY).header("anthropic-version", "2023-06-01").header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("AI service returned " + res.statusCode());
        StringBuilder text = new StringBuilder();
        Matcher tm = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(res.body());
        while (tm.find()) text.append(unjson(tm.group(1)));
        List<String[]> out = new ArrayList<>();
        return parseMatches(text.toString(), cs);
    }

    static String matchPrompt(Student st, List<Company> cs, String skills) {   // same instructions for every AI provider
        StringBuilder list = new StringBuilder();
        for (Company c : cs) list.append(c.id()).append(" | ").append(c.name()).append(" | ").append(c.industry()).append(" | ").append(c.focus()).append(" | ").append(c.about()).append(" | ").append(c.website()).append("\n");
        String prompt = "You are a careers adviser at Palm University College in Ghana. The attached PDF is the CV of " + st.name() + ", who studies " + st.program() + ". " + (skills.isEmpty() ? "" : "The student also says their skills and interests are: " + skills + ". ")
            + "Rank the partner companies below by how well each fits this student. Use the CV, the company profiles and, where you can, what is publicly known about the company. "
            + "Reply with ONLY a JSON array of at most 5 objects like {\"id\":\"C001\",\"score\":85,\"reason\":\"one short sentence\"}, best first, using only the ids given.\n\nCompanies (id | name | industry | focus | about | website):\n" + list;
        return prompt;
    }

    static List<String[]> parseMatches(String text, List<Company> cs) throws IOException {   // reads the JSON list the AI sends back
        List<String[]> out = new ArrayList<>();
        Matcher om = Pattern.compile("\\{[^{}]*\\}").matcher(text);
        while (om.find()) {
            String o = om.group();
            Matcher a = Pattern.compile("\"id\"\\s*:\\s*\"(C\\d+)\"").matcher(o), b = Pattern.compile("\"score\"\\s*:\\s*(\\d+)").matcher(o),
                    c = Pattern.compile("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(o);
            if (a.find() && b.find()) {
                String id = a.group(1);
                if (cs.stream().anyMatch(k -> k.id().equals(id))) out.add(new String[]{id, "" + Math.min(100, Integer.parseInt(b.group(1))), c.find() ? unjson(c.group(1)) : ""});
            }
        }
        if (out.isEmpty()) throw new IOException("AI reply could not be read");
        return out;
    }

    static String aiProvider() {   // "gemini" (free tier), "claude", or "" when no key is set
        String want = env("AI_PROVIDER", "").toLowerCase();
        boolean g = GEMINI_KEY != null && !GEMINI_KEY.isBlank(), c = AI_KEY != null && !AI_KEY.isBlank();
        if (want.equals("claude") && c) return "claude";
        if (want.equals("gemini") && g) return "gemini";
        return g ? "gemini" : c ? "claude" : "";
    }

    static List<String[]> geminiMatch(Student st, byte[] pdf, List<Company> cs, String skills) throws Exception {   // Google Gemini reads the PDF directly
        String body = "{\"contents\":[{\"parts\":[{\"inline_data\":{\"mime_type\":\"application/pdf\",\"data\":\"" + Base64.getEncoder().encodeToString(pdf)
            + "\"}},{\"text\":" + q(matchPrompt(st, cs, skills)) + "}]}]}";
        var req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + GEMINI_MODEL + ":generateContent")).timeout(Duration.ofSeconds(90))
            .header("x-goog-api-key", GEMINI_KEY).header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Gemini returned " + res.statusCode());
        StringBuilder text = new StringBuilder();
        Matcher tm = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(res.body());
        while (tm.find()) text.append(unjson(tm.group(1)));
        return parseMatches(text.toString(), cs);
    }

    static String sha(byte[] b) throws Exception { return b64(MessageDigest.getInstance("SHA-256").digest(b)); }

    static void analyse(HttpExchange x, Session s) throws Exception {
        if (!s.u().role().equals("STUDENT")) { json(x, 403, err("Students only")); return; }
        Map<String, String> f = form(x);
        String skills = clean(f.get("skills"), 300); boolean consent = "1".equals(f.get("consent"));
        Path cv = CVS.resolve(s.u().ref() + ".pdf");
        if (!Files.exists(cv)) { json(x, 400, err("Upload your CV first")); return; }
        if (throttled("a:" + s.u().name(), 10)) { json(x, 429, err("Too many analyses. Try again later.")); return; }
        Student st; List<Company> cs = new ArrayList<>();
        synchronized (Server.class) { st = student(s.u().ref()); for (Company c : companies) if (filled(c.id()) < c.capacity()) cs.add(c); }
        if (cs.isEmpty()) { json(x, 400, err("No companies have open slots yet")); return; }
        byte[] pdf = Files.readAllBytes(cv);
        String prov = aiProvider(), key = s.u().ref() + ":" + sha(pdf) + ":" + skills.hashCode() + ":" + cs.hashCode() + ":" + consent + ":" + prov;
        String cached = AI_CACHE.get(key);
        if (cached != null) { json(x, 200, cached); return; }   // the same CV and companies give the same answer, so reuse it
        String engine = "local", note = ""; List<String[]> res = null;
        if (prov.isEmpty()) note = "AI is not switched on (the server has no GEMINI_API_KEY or ANTHROPIC_API_KEY), so the built-in keyword matcher was used.";
        else if (!consent) note = "You did not tick the consent box, so your CV was not sent to an AI service: the built-in matcher was used.";
        else if (!AI_SLOTS.tryAcquire(20, TimeUnit.SECONDS)) note = "The AI service is busy, so the built-in matcher was used. Try again in a minute.";
        else try {
            if (prov.equals("gemini")) {
                try { res = geminiMatch(st, pdf, cs, skills); engine = "gemini"; }
                catch (Exception e) { note = "Gemini could not be used (" + e.getMessage() + "), so the built-in matcher was used."; System.err.println("Gemini match failed: " + e.getMessage()); }
            } else {
                try { res = claudeMatch(st, pdf, cs, skills, true); engine = "claude"; }
                catch (Exception e1) {
                    System.err.println("AI match (with web search) failed: " + e1.getMessage());
                    try { res = claudeMatch(st, pdf, cs, skills, false); engine = "claude"; note = "Web search was unavailable, so the AI used your CV and the company profiles only."; }
                    catch (Exception e2) { note = "The AI service could not be used (" + e2.getMessage() + "), so the built-in matcher was used."; System.err.println("AI match failed: " + e2.getMessage()); }
                }
            }
        } finally { AI_SLOTS.release(); }
        if (res == null) res = localMatch(st, pdfText(cv) + " " + skills, cs);
        Map<String, String> names = new HashMap<>();
        for (Company c : cs) names.put(c.id(), c.name());
        String out = "{\"engine\":" + q(engine) + ",\"note\":" + q(note) + ",\"matches\":"
            + arr(res.stream(), r -> "{\"id\":" + q(r[0]) + ",\"name\":" + q(names.get(r[0])) + ",\"score\":" + r[1] + ",\"reason\":" + q(r[2]) + "}") + "}";
        if (!engine.equals("local")) { if (AI_CACHE.size() > 500) AI_CACHE.clear(); AI_CACHE.put(key, out); }
        audit(s.u().name(), "cv_analysis", engine.equals("local") ? "built-in matcher" : "sent to " + engine + " with consent");
        json(x, 200, out);
    }

    static String tempPass() {   // 10 characters, no look-alikes such as O/0 or l/1
        String abc = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789", p = "";
        for (int i = 0; i < 10; i++) p += abc.charAt(RNG.nextInt(abc.length()));
        return p;
    }

    static synchronized String adminReset(Map<String, String> f) throws Exception {   // admin issues a fresh one-time password
        String name = f.getOrDefault("username", "").toLowerCase();
        User u = users.get(name);
        if (u == null || u.role().equals("ADMIN")) return err("Account not found");
        audit("admin_password_reset", name);
        String pw = tempPass(), who, email;
        if (u.role().equals("COMPANY")) { who = company(u.ref()).name(); email = company(u.ref()).email(); }
        else { who = student(u.ref()).name(); email = student(u.ref()).email(); }
        boolean onboarding = u.role().equals("COMPANY");
        users.put(name, newUser(name, u.role(), u.ref(), pw, onboarding, !onboarding));
        sessions.values().removeIf(t -> t.u().name().equals(name));
        resets.values().removeIf(r -> r.username().equals(name));
        save();
        return "{\"ok\":true,\"login\":" + q(name) + ",\"password\":" + q(pw) + ",\"name\":" + q(who) + ",\"email\":" + q(email) + "}";
    }

    static synchronized String messageStudent(Map<String, String> f) {   // school to student, e.g. the final approval notice
        App a = app(f.get("app")); String note = clean(f.get("note"), 300);
        if (a == null || note.isEmpty()) return err("Write a message first");
        a.schoolNote = note; a.date = today(); audit("message_student", a.id); tellStudent(a.sid, "Message from the school about " + a.id + ": " + note); save(); return OK;
    }

    // ---------- v6: attendance (clock in / out) and final intern reports ----------
    record Att(String sid, String cid, String clockIn, String clockOut, Double inLat, Double inLng,
               Double inAccuracy, Double inDistance, boolean inVerified, Double outLat, Double outLng,
               Double outAccuracy, Double outDistance) {
        Att(String sid, String cid, String clockIn, String clockOut) {
            this(sid, cid, clockIn, clockOut, null, null, null, null, false, null, null, null, null);
        }
    }
    record Rep(String sid, String cid, String summary, String date) {}
    static final List<Att> attendance = new ArrayList<>();
    static final List<Rep> reports = new ArrayList<>();
    static final Path REPORTS = Path.of("reports");

    static String now() { return java.time.LocalDateTime.now().withNano(0).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME); }   // server clock: students cannot fake it
    static String iso(String s) { return s.replace(' ', 'T').split("\\.")[0]; }
    static String hours(Att w) {
        if (w.clockOut().isEmpty()) return "";
        return String.format(Locale.ROOT, "%.2f", java.time.Duration.between(java.time.LocalDateTime.parse(w.clockIn()), java.time.LocalDateTime.parse(w.clockOut())).toMinutes() / 60.0);
    }
    static String pstatus(App a) {   // status of the placement this application led to ("" if none)
        for (int i = placements.size() - 1; i >= 0; i--) { Placement p = placements.get(i); if (p.sid.equals(a.sid) && p.cid.equals(a.cid)) return p.status; }
        return "";
    }
    static String placementInfo(String role, User u) {
        Placement p = role.equals("STUDENT") ? active(u.ref()) : null;
        return ",\"placement\":" + q(p == null ? "" : p.status) + ",\"company\":" + q(p == null ? "" : company(p.cid).name()) + ",\"since\":" + q(p == null ? "" : p.date) + ",\"start\":" + q(p == null ? "" : p.start) + ",\"end\":" + q(p == null ? "" : p.end);
    }

    static synchronized String clock(Map<String, String> f, User u) {   // STUDENT clocks in or out
        if (!u.role().equals("STUDENT")) return err("Students only");
        Placement p = active(u.ref());
        if (p == null || !p.status.equals("ACTIVE")) return err("Attendance opens once your internship is marked as started (Active)");
        int open = -1;
        for (int i = attendance.size() - 1; i >= 0; i--) if (attendance.get(i).sid().equals(u.ref()) && attendance.get(i).clockOut().isEmpty()) { open = i; break; }
        String act = f.getOrDefault("action", "");
        if (act.equals("in")) {
            if (open >= 0) return err("You are already clocked in");
            Company workplace = company(p.cid);
            if (workplace == null || workplace.locationLat() == null || workplace.locationLng() == null) {
                String message = student(u.ref()).name() + " tried to clock in, but " + (workplace == null ? "the assigned company" : workplace.name()) + " has no workplace location set.";
                tellStudent(u.ref(), "Your company has not set a workplace location. Please ask them to set it before clocking in.");
                if (workplace != null) tellCompany(workplace.id(), "A student could not clock in because your workplace location is missing. Please set it in Profile.");
                tellAdmins(message);
                audit("attendance_location_missing", u.ref() + " at " + p.cid);
                save();
                return err("Your company has not set a workplace location. Please ask them to set it before clocking in.");
            }
            Coordinates point = parseCoordinates(f.getOrDefault("lat", "") + "," + f.getOrDefault("lng", ""));
            Double accuracy = finiteDouble(f.get("accuracy"));
            long timestamp;
            try { timestamp = Long.parseLong(f.getOrDefault("timestamp", "")); }
            catch (NumberFormatException e) { return err("Set your location again; the timestamp is invalid."); }
            long nowMs = System.currentTimeMillis();
            if (point == null) return err("Set your location again; the coordinates are invalid.");
            if (accuracy == null || accuracy < 0 || accuracy > 100) return err("Your location accuracy is too low (worse than 100 m). Move to an open area and try again.");
            if (timestamp < nowMs - 60_000 || timestamp > nowMs + 5_000) return err("Your location fix is stale. Set your location again.");
            double distance = distanceMeters(point.lat(), point.lng(), workplace.locationLat(), workplace.locationLng());
            int geofence;
            try { geofence = Integer.parseInt(setting("geofenceMeters", "100")); } catch (NumberFormatException e) { geofence = 100; }
            if (distance > geofence) return err("You are " + Math.round(distance) + " m from the workplace; clock-in is allowed within " + geofence + " m.");
            attendance.add(new Att(u.ref(), p.cid, now(), "", point.lat(), point.lng(), accuracy, distance, true, null, null, null, null));
            audit("attendance_clock_in", u.ref() + " verified within " + Math.round(distance) + " m");
        } else if (act.equals("out")) {
            if (open < 0) return err("You are not clocked in");
            Att a = attendance.get(open);
            Coordinates point = null;
            String lat = f.getOrDefault("lat", "").trim(), lng = f.getOrDefault("lng", "").trim();
            Double accuracy = finiteDouble(f.get("accuracy"));
            if (!lat.isEmpty() || !lng.isEmpty()) {
                point = parseCoordinates(lat + "," + lng);
                if (point == null || accuracy == null || accuracy < 0) return err("The clock-out location is invalid. Try again or clock out without location.");
            }
            Company workplace = company(a.cid());
            Double distance = point != null && workplace != null && workplace.locationLat() != null && workplace.locationLng() != null
                ? distanceMeters(point.lat(), point.lng(), workplace.locationLat(), workplace.locationLng()) : null;
            attendance.set(open, new Att(a.sid(), a.cid(), a.clockIn(), now(), a.inLat(), a.inLng(), a.inAccuracy(), a.inDistance(), a.inVerified(),
                point == null ? null : point.lat(), point == null ? null : point.lng(), accuracy, distance));
            audit("attendance_clock_out", u.ref());
        } else return err("Unknown action");
        save(); return OK;
    }

    static Double finiteDouble(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
            + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static synchronized String startIntern(Map<String, String> f, User u) {   // COMPANY marks that the intern has started
        if (!u.role().equals("COMPANY")) return err("Companies only");
        Placement p = active(f.getOrDefault("sid", ""));
        if (p == null || !p.cid.equals(u.ref()) || !p.status.equals("PENDING")) return err("Nothing to start");
        p.status = "ACTIVE"; if (p.start.isEmpty()) p.start = today();
        audit("internship_started", p.sid + " at " + p.cid); tellStudent(p.sid, "Your internship at " + company(p.cid).name() + " has started. You can now clock in under Attendance."); tellAdmins(student(p.sid).name() + " started at " + company(p.cid).name() + "."); save(); return OK;
    }

    static void uploadReport(HttpExchange x, User u) throws IOException {   // COMPANY uploads the final report on an intern (PDF)
        if (!u.role().equals("COMPANY")) { json(x, 403, err("Companies only")); return; }
        Map<String, String> qp = query(x);
        String sid = qp.getOrDefault("sid", ""), summary = clean(qp.get("summary"), 300);
        byte[] b = x.getRequestBody().readNBytes(5_242_881);
        if (b.length > 5_242_880) { json(x, 413, err("The report must be 5 MB or smaller")); return; }
        if (b.length < 5 || !new String(b, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-")) { json(x, 400, err("Please upload the report as a PDF file")); return; }
        synchronized (Server.class) {
            boolean mine = placements.stream().anyMatch(k -> k.sid.equals(sid) && k.cid.equals(u.ref()) && !k.status.equals("CANCELLED"));
            if (!mine) { json(x, 400, err("That student is not one of your interns")); return; }
            Files.createDirectories(REPORTS);
            Files.write(REPORTS.resolve(sid + "_" + u.ref() + ".pdf"), b);   // name built from known ids: no path tricks
            reports.removeIf(k -> k.sid().equals(sid) && k.cid().equals(u.ref()));
            reports.add(new Rep(sid, u.ref(), summary, today()));
            audit("report_uploaded", sid + " by " + u.ref()); tellAdmins("A final report was uploaded for " + student(sid).name() + ".");
            save();
        }
        json(x, 200, OK);
    }

    static synchronized void report(HttpExchange x, Session s) throws IOException {   // admin (any) or the company that wrote it
        Map<String, String> qp = query(x); User u = s.u();
        String sid = qp.getOrDefault("sid", ""), cid = qp.getOrDefault("cid", "");
        boolean found = reports.stream().anyMatch(k -> k.sid().equals(sid) && k.cid().equals(cid));
        boolean ok = found && (u.role().equals("ADMIN") || (u.role().equals("COMPANY") && cid.equals(u.ref())));
        Path f = ok ? REPORTS.resolve(sid + "_" + cid + ".pdf") : null;
        if (f == null || !Files.exists(f)) { json(x, 404, err("Report not found")); return; }
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"Intern-report-" + sid + ".pdf\"");
        send(x, 200, "application/pdf", Files.readAllBytes(f));
    }

    // ---------- v7: staff, settings, student list, privacy, withdrawals, dates, evaluations, logbook ----------
    static String letterCode(App a) { try { return hmac(a.id + "|" + a.sid + "|" + a.cid); } catch (Exception e) { return ""; } }

    static void verifyLetter(HttpExchange x) throws Exception {   // public: confirms that an approval letter is genuine
        if (throttled("vl:" + ip(x), 30)) { json(x, 429, err("Too many checks. Try again later.")); return; }
        Map<String, String> qp = query(x); String code = qp.getOrDefault("code", "").trim().toUpperCase();
        synchronized (Server.class) {
            App a = app(qp.getOrDefault("ref", "").trim().toUpperCase());
            if (a != null && a.status.equals("ACCEPTED") && !pstatus(a).equals("CANCELLED") && hmac(a.id + "|" + a.sid + "|" + a.cid).equals(code)) {
                json(x, 200, "{\"valid\":true,\"student\":" + q(student(a.sid).name()) + ",\"company\":" + q(company(a.cid).name()) + ",\"date\":" + q(a.date) + "}"); return;
            }
        }
        json(x, 200, "{\"valid\":false}");
    }

    static synchronized String markRead(User u) {
        for (int i = 0; i < notes.size(); i++) { Note n = notes.get(i); if (n.user().equals(u.name()) && !n.read()) notes.set(i, new Note(n.user(), n.at(), n.text(), true)); }
        save(); return OK;
    }

    static synchronized String addStaff(Map<String, String> f) throws Exception {   // another school staff member with admin access
        String em = clean(f.get("email"), 120).toLowerCase();
        if (!ANY_EMAIL.matcher(em).matches()) return err("Enter the staff member's email address");
        if (users.containsKey(em)) return err("That account already exists");
        String pw = tempPass();
        users.put(em, newUser(em, "ADMIN", "", pw));
        audit("staff_created", em);
        save(); return "{\"ok\":true,\"login\":" + q(em) + ",\"password\":" + q(pw) + ",\"name\":" + q(em) + ",\"email\":" + q(em) + "}";
    }

    static synchronized String saveSettings(Map<String, String> f) {
        String dl = f.getOrDefault("deadline", "").trim(), rd = f.getOrDefault("retentionDays", "").trim();
        String gm = f.getOrDefault("geofenceMeters", "").trim();
        if (!dl.isEmpty()) { try { LocalDate.parse(dl); } catch (Exception e) { return err("The deadline must be a valid date"); } }
        if (!rd.isEmpty()) { try { int n = Integer.parseInt(rd); if (n < 30 || n > 3650) return err("Keep CVs between 30 and 3650 days"); } catch (NumberFormatException e) { return err("Retention must be a number of days"); } }
        if (!gm.isEmpty()) { try { int n = Integer.parseInt(gm); if (n < 10 || n > 5000) return err("Geofence must be between 10 and 5000 metres"); } catch (NumberFormatException e) { return err("Geofence must be a number of metres"); } }
        settings.put("deadline", dl); if (!rd.isEmpty()) settings.put("retentionDays", rd); if (!gm.isEmpty()) settings.put("geofenceMeters", gm);
        audit("settings_changed", "deadline=" + dl + " retentionDays=" + rd + " geofenceMeters=" + setting("geofenceMeters", "100")); save(); return OK;
    }

    static synchronized String saveRegister(Map<String, String> f) {   // the school's own list of students allowed to sign up
        Map<String, String> incoming = new LinkedHashMap<>();
        for (String line : f.getOrDefault("list", "").split("[\\r\\n]+")) {
            String[] p = line.split("[,;\\t]", 2); String em = p[0].trim().toLowerCase();
            if (em.isEmpty()) continue;
            if (!ANY_EMAIL.matcher(em).matches()) return err("Not a valid email address: " + clean(em, 60));
            incoming.put(em, p.length > 1 ? clean(p[1], 60) : "");
            if (incoming.size() > 5000) return err("The import can contain up to 5000 students");
        }
        String mode = f.getOrDefault("mode", "merge");
        if (!mode.equals("merge") && !mode.equals("replace")) return err("Choose merge or replace for the student list import");
        if (!mode.equals("replace") && register.size() - incoming.keySet().stream().filter(register::containsKey).count() + incoming.size() > 5000)
            return err("The approved student list can contain up to 5000 students");
        if (mode.equals("replace")) { register.clear(); register.putAll(incoming); } else register.putAll(incoming);
        audit("student_list_changed", mode + " " + incoming.size() + " entries; total=" + register.size()); save(); return OK;
    }

    static synchronized String eraseStudent(Map<String, String> f) {
        String sid = f.getOrDefault("sid", ""); Student s = student(sid);
        if (s == null) return err("Student not found");
        erase(sid); audit("student_erased", sid + " " + s.name()); save(); return OK;
    }

    static synchronized String deleteMe(Map<String, String> f, User u) throws Exception {   // a student deletes their own account and data
        if (!u.role().equals("STUDENT")) return err("Students only");
        User cur = users.get(u.name());
        if (cur == null || !MessageDigest.isEqual(hash(f.getOrDefault("password", ""), cur.salt()), cur.hash())) return err("Your password is not correct");
        if (placements.stream().anyMatch(p -> p.sid.equals(u.ref()) && !p.status.equals("CANCELLED"))) return err("You have a placement, so the school has to keep your records. Ask the school office to remove your data.");
        String sid = u.ref(), name = u.name();
        erase(sid); audit(name, "account_deleted", sid);
        sessions.values().removeIf(t -> t.u().name().equals(name));
        save(); return OK;
    }

    static synchronized String withdraw(Map<String, String> f, User u) {
        App a = app(f.get("app"));
        if (!u.role().equals("STUDENT") || a == null || !a.sid.equals(u.ref()) || !(a.status.equals("SUBMITTED") || a.status.equals("FORWARDED"))) return err("This application cannot be withdrawn");
        boolean forwarded = a.status.equals("FORWARDED");
        a.status = "WITHDRAWN"; a.date = today();
        audit("withdraw", a.id); tellAdmins(student(a.sid).name() + " withdrew application " + a.id + ".");
        if (forwarded) tellCompany(a.cid, "Application " + a.id + " was withdrawn by the student.");
        save(); return OK;
    }

    static synchronized String setDates(Map<String, String> f, User u) {   // internship start and end dates (company or school)
        Placement p = active(f.getOrDefault("sid", ""));
        if (p == null || !(u.role().equals("ADMIN") || (u.role().equals("COMPANY") && p.cid.equals(u.ref())))) return err("Not allowed");
        String st = f.getOrDefault("start", "").trim(), en = f.getOrDefault("end", "").trim();
        try {
            if (!st.isEmpty()) LocalDate.parse(st);
            if (!en.isEmpty()) LocalDate.parse(en);
            if (!st.isEmpty() && !en.isEmpty() && LocalDate.parse(en).isBefore(LocalDate.parse(st))) return err("The end date is before the start date");
        } catch (Exception e) { return err("Please enter valid dates"); }
        p.start = st; p.end = en;
        audit("dates_set", p.sid + " " + st + " to " + en); tellStudent(p.sid, "Your internship dates are " + (st.isEmpty() ? "not set" : st) + " to " + (en.isEmpty() ? "not set" : en) + ".");
        save(); return OK;
    }

    static synchronized String complete(Map<String, String> f, User u) {   // the company closes an internship
        Placement p = active(f.getOrDefault("sid", ""));
        if (!u.role().equals("COMPANY") || p == null || !p.cid.equals(u.ref()) || !p.status.equals("ACTIVE")) return err("Only an active internship can be completed");
        p.status = "COMPLETED"; if (p.end.isEmpty() || p.end.compareTo(today()) > 0) p.end = today();
        audit("internship_completed", p.sid); tellStudent(p.sid, "Your internship at " + company(p.cid).name() + " is marked as completed.");
        tellAdmins(student(p.sid).name() + " completed the internship at " + company(p.cid).name() + ".");
        save(); return OK;
    }

    static EvaluationForm activeEvaluationForm() {
        return evaluationForms.stream().filter(form -> form.status().equals("ACTIVE")).findFirst().orElse(null);
    }

    static long evaluationResponseCount(String formId) {
        return evaluationResponses.stream().filter(response -> response.formId().equals(formId)).count();
    }

    static String evaluationFormJson(EvaluationForm form) {
        return "{\"id\":" + q(form.id()) + ",\"title\":" + q(form.title()) + ",\"status\":" + q(form.status())
            + ",\"createdOn\":" + q(form.createdOn()) + ",\"responses\":" + evaluationResponseCount(form.id())
            + ",\"questions\":" + arr(evaluationQuestions.stream().filter(question -> question.formId().equals(form.id()))
                .sorted(Comparator.comparingInt(EvaluationQuestion::order)), question -> "{\"id\":" + q(question.id())
                    + ",\"prompt\":" + q(question.prompt()) + ",\"type\":" + q(question.type())
                    + ",\"required\":" + question.required() + ",\"order\":" + question.order()
                    + ",\"options\":" + arr(Arrays.stream(question.options().split("\\n")).filter(option -> !option.isBlank()), Server::q) + "}") + "}";
    }

    static synchronized void evaluationForms(HttpExchange x, User u) throws IOException {
        if (!(u.role().equals("ADMIN") || u.role().equals("COMPANY"))) { json(x, 403, err("Evaluations are available to companies and administrators only.")); return; }
        EvaluationForm active = activeEvaluationForm();
        if (u.role().equals("COMPANY")) {
            json(x, 200, "{\"active\":" + (active == null ? "null" : evaluationFormJson(active)) + "}");
            return;
        }
        json(x, 200, "{\"forms\":" + arr(evaluationForms.stream().sorted(Comparator.comparing(EvaluationForm::createdOn).reversed()), Server::evaluationFormJson)
            + ",\"active\":" + (active == null ? "null" : evaluationFormJson(active)) + "}");
    }

    static synchronized String saveEvaluationForm(Map<String, String> f) {
        String id = f.getOrDefault("id", "").trim();
        EvaluationForm current = id.isEmpty() ? null : evaluationForms.stream().filter(form -> form.id().equals(id)).findFirst().orElse(null);
        if (!id.isEmpty() && current == null) return err("Evaluation form not found.");
        if (current != null && current.status().equals("ARCHIVED")) return err("Archived forms cannot be edited.");
        if (current != null && evaluationResponseCount(id) > 0) return err("This form already has responses. Duplicate it to make changes.");
        String rawTitle = f.getOrDefault("title", "");
        if (rawTitle.length() > 100) return err("Form titles must be 100 characters or fewer.");
        String title = clean(rawTitle, 100), status = f.getOrDefault("status", "DRAFT").toUpperCase(Locale.ROOT);
        if (title.length() < 3) return err("Enter an evaluation form title.");
        if (!Set.of("DRAFT", "ACTIVE").contains(status)) return err("Form status must be DRAFT or ACTIVE.");
        int count;
        try { count = Integer.parseInt(f.getOrDefault("questionCount", "")); }
        catch (NumberFormatException e) { return err("Add at least one evaluation question."); }
        if (count < 1 || count > 30) return err("Forms must have between 1 and 30 questions.");
        List<EvaluationQuestion> questions = new ArrayList<>();
        Set<String> allowedTypes = Set.of("rating", "short_text", "long_text", "single_choice", "yes_no");
        String formId = current == null ? nid("EF", evaluationForms.stream().map(EvaluationForm::id)) : current.id();
        for (int i = 0; i < count; i++) {
            String rawPrompt = f.getOrDefault("q" + i + "Prompt", ""), type = f.getOrDefault("q" + i + "Type", "").trim();
            if (rawPrompt.length() > 300) return err("Question prompts must be 300 characters or fewer.");
            String prompt = clean(rawPrompt, 300);
            if (prompt.length() < 2 || !allowedTypes.contains(type)) return err("Question " + (i + 1) + " needs a prompt and a supported type.");
            List<String> options = new ArrayList<>();
            for (String rawOption : f.getOrDefault("q" + i + "Options", "").split("[\\r\\n]+")) {
                if (rawOption.length() > 80) return err("Choice options must be 80 characters or fewer.");
                String option = clean(rawOption, 80);
                if (!option.isEmpty() && options.stream().noneMatch(existing -> existing.equalsIgnoreCase(option))) options.add(option);
            }
            if (type.equals("single_choice") && (options.size() < 2 || options.size() > 20)) return err("Single-choice questions need 2 to 20 options.");
            if (!type.equals("single_choice")) options.clear();
            String questionId = nid("EQ", Stream.concat(evaluationQuestions.stream().map(EvaluationQuestion::id), questions.stream().map(EvaluationQuestion::id)));
            questions.add(new EvaluationQuestion(questionId, formId, prompt, type,
                "1".equals(f.get("q" + i + "Required")), i + 1, String.join("\n", options)));
        }
        if (status.equals("ACTIVE")) evaluationForms.replaceAll(form -> form.status().equals("ACTIVE")
            ? new EvaluationForm(form.id(), form.title(), "ARCHIVED", form.createdOn()) : form);
        if (current == null) evaluationForms.add(new EvaluationForm(formId, title, status, today()));
        else evaluationForms.replaceAll(form -> form.id().equals(formId) ? new EvaluationForm(formId, title, status, form.createdOn()) : form);
        evaluationQuestions.removeIf(question -> question.formId().equals(formId));
        evaluationQuestions.addAll(questions);
        audit(current == null ? "evaluation_form_created" : "evaluation_form_updated", formId + " " + title);
        save();
        return "{\"ok\":true,\"id\":" + q(formId) + "}";
    }

    static synchronized String duplicateEvaluationForm(Map<String, String> f) {
        EvaluationForm source = evaluationForms.stream().filter(form -> form.id().equals(f.getOrDefault("id", ""))).findFirst().orElse(null);
        if (source == null) return err("Evaluation form not found.");
        String newId = nid("EF", evaluationForms.stream().map(EvaluationForm::id));
        evaluationForms.add(new EvaluationForm(newId, clean(source.title() + " copy", 100), "DRAFT", today()));
        List<EvaluationQuestion> copies = new ArrayList<>();
        for (EvaluationQuestion question : evaluationQuestions.stream().filter(item -> item.formId().equals(source.id())).toList()) {
            String newQuestionId = nid("EQ", Stream.concat(evaluationQuestions.stream().map(EvaluationQuestion::id), copies.stream().map(EvaluationQuestion::id)));
            copies.add(new EvaluationQuestion(newQuestionId, newId, question.prompt(), question.type(), question.required(), question.order(), question.options()));
        }
        evaluationQuestions.addAll(copies);
        audit("evaluation_form_duplicated", source.id() + " as " + newId);
        save();
        return "{\"ok\":true,\"id\":" + q(newId) + "}";
    }

    static synchronized String archiveEvaluationForm(Map<String, String> f) {
        String id = f.getOrDefault("id", "");
        EvaluationForm form = evaluationForms.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (form == null) return err("Evaluation form not found.");
        evaluationForms.replaceAll(item -> item.id().equals(id) ? new EvaluationForm(item.id(), item.title(), "ARCHIVED", item.createdOn()) : item);
        audit("evaluation_form_archived", id);
        save();
        return OK;
    }

    static boolean evaluationEligible(Placement placement) {
        if (placement.status.equals("COMPLETED")) return true;
        if (placement.end == null || placement.end.isBlank()) return false;
        try { return !LocalDate.parse(placement.end).isAfter(LocalDate.now()); }
        catch (Exception e) { return false; }
    }

    static synchronized String submitEvaluation(Map<String, String> f, User u) {
        String sid = f.getOrDefault("sid", ""), formId = f.getOrDefault("formId", "");
        if (!u.role().equals("COMPANY")) return err("Companies only.");
        Placement placement = placements.stream().filter(item -> item.sid.equals(sid) && item.cid.equals(u.ref()) && !item.status.equals("CANCELLED"))
            .findFirst().orElse(null);
        if (placement == null) return err("That student is not one of your interns.");
        if (!evaluationEligible(placement)) return err("An evaluation is available only after the internship is completed or its end date has passed.");
        if (evaluationResponses.stream().anyMatch(response -> response.sid().equals(sid) && response.cid().equals(u.ref())))
            return err("This intern has already been evaluated. The submission is locked.");
        EvaluationForm form = activeEvaluationForm();
        if (form == null || !form.id().equals(formId)) return err("The active evaluation form changed. Reload and try again.");
        List<EvaluationQuestion> questions = evaluationQuestions.stream().filter(question -> question.formId().equals(form.id()))
            .sorted(Comparator.comparingInt(EvaluationQuestion::order)).toList();
        if (questions.isEmpty()) return err("The active evaluation form has no questions.");
        List<String> answers = new ArrayList<>();
        for (EvaluationQuestion question : questions) {
            String value = f.getOrDefault("a_" + question.id(), "").trim();
            if (question.required() && value.isEmpty()) return err("Answer the required question: " + question.prompt());
            if (value.isEmpty()) { answers.add(q(question.id()) + ":null"); continue; }
            switch (question.type()) {
                case "rating" -> {
                    int rating;
                    try { rating = Integer.parseInt(value); } catch (NumberFormatException e) { return err("Ratings must be whole numbers from 1 to 5."); }
                    if (rating < 1 || rating > 5) return err("Ratings must be from 1 to 5.");
                    answers.add(q(question.id()) + ":" + rating);
                }
                case "short_text" -> {
                    if (value.length() > 200) return err("Short answers must be 200 characters or fewer.");
                    value = clean(value, 200);
                    answers.add(q(question.id()) + ":" + q(value));
                }
                case "long_text" -> {
                    if (value.length() > 2000) return err("Long answers must be 2000 characters or fewer.");
                    value = clean(value, 2000);
                    answers.add(q(question.id()) + ":" + q(value));
                }
                case "single_choice" -> {
                    List<String> options = Arrays.stream(question.options().split("\\n")).toList();
                    if (!options.contains(value)) return err("Choose a valid option for: " + question.prompt());
                    answers.add(q(question.id()) + ":" + q(value));
                }
                case "yes_no" -> {
                    if (!(value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("no"))) return err("Choose Yes or No for: " + question.prompt());
                    answers.add(q(question.id()) + ":" + q(value.equalsIgnoreCase("yes") ? "Yes" : "No"));
                }
                default -> { return err("Unsupported evaluation question type."); }
            }
        }
        String json = "{" + String.join(",", answers) + "}";
        evaluationResponses.add(new EvaluationResponse(sid, u.ref(), form.id(), json, now()));
        audit("evaluation_submitted", sid + " by " + u.ref());
        tellAdmins("An evaluation was submitted for " + student(sid).name() + ".");
        save();
        return OK;
    }

    static Double responseRating(EvaluationResponse response, String questionId) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(questionId) + "\"\\s*:\\s*(\\d+)").matcher(response.answers());
        if (!matcher.find()) return null;
        try { return Double.valueOf(matcher.group(1)); } catch (NumberFormatException e) { return null; }
    }

    static synchronized void evaluationReport(HttpExchange x, User u) throws IOException {
        if (!u.role().equals("ADMIN")) { json(x, 403, err("Administrators only.")); return; }
        List<EvaluationResponse> responses = evaluationResponses.stream().sorted(Comparator.comparing(EvaluationResponse::submittedOn).reversed()).toList();
        List<EvaluationQuestion> ratings = evaluationQuestions.stream().filter(question -> question.type().equals("rating")).toList();
        double[] totals = {0, 0};
        Map<String, double[]> byStudent = new TreeMap<>(String.CASE_INSENSITIVE_ORDER), byCompany = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String rows = arr(responses.stream(), response -> {
            double sum = 0; int count = 0;
            for (EvaluationQuestion question : ratings) {
                if (!question.formId().equals(response.formId())) continue;
                Double rating = responseRating(response, question.id());
                if (rating != null) { sum += rating; count++; }
            }
            totals[0] += sum; totals[1] += count;
            byStudent.computeIfAbsent(response.sid(), key -> new double[2]);
            byCompany.computeIfAbsent(response.cid(), key -> new double[2]);
            byStudent.get(response.sid())[0] += sum; byStudent.get(response.sid())[1] += count;
            byCompany.get(response.cid())[0] += sum; byCompany.get(response.cid())[1] += count;
            Student st = student(response.sid()); Company c = company(response.cid());
            EvaluationForm form = evaluationForms.stream().filter(item -> item.id().equals(response.formId())).findFirst().orElse(null);
            return "{\"sid\":" + q(response.sid()) + ",\"student\":" + q(st == null ? "" : st.name())
                + ",\"studentEmail\":" + q(st == null ? "" : st.email()) + ",\"cid\":" + q(response.cid())
                + ",\"company\":" + q(c == null ? "" : c.name()) + ",\"companyEmail\":" + q(c == null ? "" : c.email())
                + ",\"formId\":" + q(response.formId()) + ",\"form\":" + q(form == null ? "" : form.title())
                + ",\"answers\":" + q(response.answers()) + ",\"date\":" + q(response.submittedOn()) + "}";
        });
        String studentsJson = arr(byStudent.entrySet().stream(), entry -> "{\"id\":" + q(entry.getKey()) + ",\"name\":" + q(student(entry.getKey()).name())
            + ",\"email\":" + q(student(entry.getKey()).email()) + ",\"responses\":" + responses.stream().filter(response -> response.sid().equals(entry.getKey())).count()
            + ",\"averageRating\":" + (entry.getValue()[1] == 0 ? "null" : String.format(Locale.ROOT, "%.2f", entry.getValue()[0] / entry.getValue()[1])) + "}");
        String companiesJson = arr(byCompany.entrySet().stream(), entry -> "{\"id\":" + q(entry.getKey()) + ",\"name\":" + q(company(entry.getKey()).name())
            + ",\"email\":" + q(company(entry.getKey()).email()) + ",\"responses\":" + responses.stream().filter(response -> response.cid().equals(entry.getKey())).count()
            + ",\"averageRating\":" + (entry.getValue()[1] == 0 ? "null" : String.format(Locale.ROOT, "%.2f", entry.getValue()[0] / entry.getValue()[1])) + "}");
        json(x, 200, "{\"responses\":" + rows + ",\"byStudent\":" + studentsJson + ",\"byCompany\":" + companiesJson
            + ",\"aggregate\":{\"responses\":" + responses.size() + ",\"averageRating\":"
            + (totals[1] == 0 ? "null" : String.format(Locale.ROOT, "%.2f", totals[0] / totals[1])) + "}}");
    }

    static synchronized String addLog(Map<String, String> f, User u) {   // student's weekly logbook entry
        Placement p = u.role().equals("STUDENT") ? active(u.ref()) : null;
        if (p == null || !p.status.equals("ACTIVE")) return err("The logbook opens once your internship is Active");
        String body = clean(f.get("body"), 1500);
        if (body.length() < 20) return err("Write at least a couple of sentences about your week");
        String week = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString();
        logs.removeIf(l -> l.sid().equals(u.ref()) && l.week().equals(week) && !l.status().equals("APPROVED"));   // sending the same week again replaces it
        logs.add(new LogEntry(nid("L", logs.stream().map(LogEntry::id)), u.ref(), p.cid, week, body, "SUBMITTED", "", today()));
        tellCompany(p.cid, student(u.ref()).name() + " submitted a weekly logbook entry.");
        save(); return OK;
    }

    static synchronized String reviewLog(Map<String, String> f, User u) {   // company supervisor approves or returns an entry
        for (int i = 0; i < logs.size(); i++) {
            LogEntry l = logs.get(i);
            if (!l.id().equals(f.get("id"))) continue;
            String act = f.getOrDefault("action", "");
            if (!u.role().equals("COMPANY") || !l.cid().equals(u.ref()) || !l.status().equals("SUBMITTED") || !(act.equals("approve") || act.equals("return"))) return err("Not allowed");
            logs.set(i, new LogEntry(l.id(), l.sid(), l.cid(), l.week(), l.body(), act.equals("approve") ? "APPROVED" : "RETURNED", clean(f.get("note"), 300), today()));
            tellStudent(l.sid(), "Your logbook entry for the week of " + l.week() + " was " + (act.equals("approve") ? "approved." : "returned with a comment."));
            save(); return OK;
        }
        return err("Entry not found");
    }

    // ---------- CSV reports (admin) ----------
    static String cell(String s) {   // quote + neutralise spreadsheet formula injection
        if (s.matches("^[=+\\-@].*")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static String htmlCell(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String auditEmail(String actor) {
        User user = users.get(actor);
        if (user == null) return ANY_EMAIL.matcher(actor).matches() ? actor : "";
        if (user.role().equals("COMPANY")) {
            Company company = company(user.ref());
            return company == null ? "" : company.email();
        }
        if (user.role().equals("STUDENT")) {
            Student student = student(user.ref());
            return student == null ? "" : student.email();
        }
        return ANY_EMAIL.matcher(actor).matches() ? actor : "";
    }

    static synchronized void export(HttpExchange x) throws IOException {
        Map<String, String> qp = query(x);
        String t = qp.getOrDefault("type", ""), from = qp.getOrDefault("from", ""), to = qp.getOrDefault("to", "");
        String search = qp.getOrDefault("search", "").trim().toLowerCase(Locale.ROOT);
        String cid = qp.getOrDefault("cid", ""), sid = qp.getOrDefault("sid", "");
        String status = qp.getOrDefault("status", ""), location = qp.getOrDefault("location", "");
        String sort = qp.getOrDefault("sort", "name"), direction = qp.getOrDefault("direction", "asc");
        boolean descending = direction.equalsIgnoreCase("desc");
        java.util.function.Predicate<String> within = d -> {
            String date = d == null ? "" : d.substring(0, Math.min(10, d.length()));
            return (from.isEmpty() || date.compareTo(from) >= 0) && (to.isEmpty() || date.compareTo(to) <= 0);
        };
        List<String[]> rows = new ArrayList<>();
        switch (t) {
            case "students" -> {
                rows.add(new String[]{"ID", "Name", "Email", "Programme", "Placed"});
                students.forEach(student -> rows.add(new String[]{student.id(), student.name(), student.email(), student.program(), active(student.id()) != null ? "Yes" : "No"}));
            }
            case "companies" -> {
                rows.add(new String[]{"ID", "Name", "Email", "Industry", "Focus", "Capacity", "Filled"});
                companies.forEach(company -> rows.add(new String[]{company.id(), company.name(), company.email(), company.industry(), company.focus(), "" + company.capacity(), "" + filled(company.id())}));
            }
            case "placements" -> {
                rows.add(new String[]{"Student", "Student email", "Company", "Company email", "Programme", "Status", "Placed on", "Start", "End"});
                placements.stream().filter(placement -> within.test(placement.date)).forEach(placement -> {
                    Student student = student(placement.sid); Company company = company(placement.cid);
                    rows.add(new String[]{student.name(), student.email(), company.name(), company.email(), student.program(), placement.status, placement.date, placement.start, placement.end});
                });
            }
            case "applications" -> {
                rows.add(new String[]{"Ref", "Student", "Student email", "Company", "Company email", "Programme", "Status", "Updated"});
                apps.stream().filter(application -> within.test(application.date)).forEach(application -> {
                    Student student = student(application.sid); Company company = company(application.cid);
                    rows.add(new String[]{application.id, student.name(), student.email(), cname(application), company == null ? "" : company.email(),
                        student.program(), application.status, application.date});
                });
            }
            case "attendance" -> {
                rows.add(new String[]{"Student", "Student email", "Company", "Company email", "Clock in", "Clock out", "Hours", "Status", "Location verification", "Distance (m)"});
                attendance.stream().filter(record -> within.test(record.clockIn()))
                    .filter(record -> sid.isEmpty() || sid.equals(record.sid()))
                    .filter(record -> cid.isEmpty() || cid.equals(record.cid()))
                    .filter(record -> search.isEmpty() || (student(record.sid()).name() + " " + student(record.sid()).email() + " " + record.sid()).toLowerCase(Locale.ROOT).contains(search))
                    .filter(record -> status.isEmpty() || (status.equals("clocked_in") ? record.clockOut().isEmpty()
                        : status.equals("completed") ? !record.clockOut().isEmpty() : status.equals("flagged") && !record.inVerified()))
                    .filter(record -> location.isEmpty() || (location.equals("verified") ? record.inVerified() : location.equals("failed") && !record.inVerified()))
                    .forEach(record -> {
                        Student student = student(record.sid()); Company company = company(record.cid());
                        rows.add(new String[]{student.name(), student.email(), company.name(), company.email(), record.clockIn().replace('T', ' '),
                            record.clockOut().replace('T', ' '), hours(record), record.clockOut().isEmpty() ? "Clocked in" : "Completed",
                            record.inVerified() ? "Verified on site" : "Failed / not verified",
                            record.inDistance() == null ? "" : String.format(Locale.ROOT, "%.1f", record.inDistance())});
                    });
            }
            case "evaluations" -> {
                rows.add(new String[]{"Student", "Student email", "Company", "Company email", "Form", "Answers", "Submitted"});
                evaluationResponses.stream().filter(response -> within.test(response.submittedOn())).forEach(response -> {
                    Student student = student(response.sid()); Company company = company(response.cid());
                    EvaluationForm form = evaluationForms.stream().filter(item -> item.id().equals(response.formId())).findFirst().orElse(null);
                    rows.add(new String[]{student == null ? "" : student.name(), student == null ? "" : student.email(),
                        company == null ? "" : company.name(), company == null ? "" : company.email(), form == null ? "" : form.title(),
                        response.answers(), response.submittedOn().replace('T', ' ')});
                });
            }
            case "reports" -> {
                rows.add(new String[]{"Student", "Student email", "Company", "Company email", "Summary", "Date"});
                reports.stream().filter(record -> within.test(record.date())).forEach(record -> rows.add(new String[]{
                    student(record.sid()).name(), student(record.sid()).email(), company(record.cid()).name(), company(record.cid()).email(), record.summary(), record.date()}));
            }
            case "audit" -> {
                rows.add(new String[]{"When", "Actor", "Email", "Action", "Detail"});
                audits.stream().filter(record -> within.test(record.at())).forEach(record -> rows.add(new String[]{
                    record.at().replace('T', ' '), record.actor(), auditEmail(record.actor()), record.action(), record.detail()}));
            }
            default -> { json(x, 400, err("Unknown report")); return; }
        }
        int nameIndex = switch (t) { case "students", "companies" -> 1; case "placements" -> 0; case "applications" -> 1;
            case "attendance", "evaluations", "reports" -> 0; case "audit" -> 1; default -> 0; };
        int emailIndex = switch (t) { case "students", "companies" -> 2; case "placements" -> 1; case "applications" -> 2;
            case "attendance", "evaluations", "reports" -> 1; case "audit" -> 2; default -> 0; };
        int companyIndex = t.equals("attendance") ? 2 : nameIndex;
        int dateIndex = t.equals("attendance") ? 4 : -1;
        int hoursIndex = t.equals("attendance") ? 6 : -1;
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.SECONDARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        Comparator<String[]> comparator;
        if (t.equals("attendance") && sort.equals("date")) comparator = Comparator.comparing(row -> row[dateIndex]);
        else if (t.equals("attendance") && sort.equals("hours")) comparator = Comparator.comparingDouble(row -> row[hoursIndex].isEmpty() ? -1 : Double.parseDouble(row[hoursIndex]));
        else if (t.equals("attendance") && sort.equals("company")) comparator = (a, b) -> collator.compare(a[companyIndex], b[companyIndex]);
        else comparator = (a, b) -> collator.compare(a[sort.equals("email") ? emailIndex : nameIndex], b[sort.equals("email") ? emailIndex : nameIndex]);
        if (descending) comparator = comparator.reversed();
        if (rows.size() > 1) rows.subList(1, rows.size()).sort(comparator.thenComparing(row -> row[nameIndex], collator));
        if (qp.getOrDefault("format", "").equals("print")) {
            StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Palm ")
                .append(htmlCell(t)).append(" report · ").append(today())
                .append("</title><style>body{font:14px Arial,sans-serif;color:#14251c;margin:2rem}h1{color:#0a3d2b}p{color:#53645a}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccd5cf;padding:.55rem;text-align:left;vertical-align:top}th{background:#edf3ef}button{padding:.6rem 1rem;margin-bottom:1rem}@media print{body{margin:0}button{display:none}thead{display:table-header-group}tr{break-inside:avoid}}</style></head><body><button onclick=\"window.print()\">Print / Save as PDF</button><h1>Palm ")
                .append(htmlCell(t)).append(" report</h1><p>Generated ").append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" · ").append(rows.size() - 1).append(" records</p><table><thead><tr>");
            for (String column : rows.get(0)) html.append("<th>").append(htmlCell(column)).append("</th>");
            html.append("</tr></thead><tbody>");
            for (int i = 1; i < rows.size(); i++) {
                html.append("<tr>");
                for (String value : rows.get(i)) html.append("<td>").append(htmlCell(value)).append("</td>");
                html.append("</tr>");
            }
            html.append("</tbody></table></body></html>");
            audit("export_print", t + " " + from + " " + to);
            x.getResponseHeaders().set("Content-Disposition", "inline; filename=\"palm-" + t + "-" + today() + ".html\"");
            send(x, 200, "text/html", html.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        String csv = rows.stream().map(r -> Arrays.stream(r).map(Server::cell).collect(Collectors.joining(","))).collect(Collectors.joining("\r\n"));
        audit("export", t + " " + from + " " + to);
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"palm-" + t + "-" + today() + ".csv\"");
        send(x, 200, "text/csv", ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- Smart Match ----------
    static Set<String> words(String s) {
        return Arrays.stream(s.toLowerCase().split("[^a-z]+")).filter(w -> w.length() > 2 && !STOP.contains(w)).collect(Collectors.toCollection(TreeSet::new));
    }

    static synchronized void match(HttpExchange x) throws IOException {
        Student s = student(query(x).get("student"));
        if (s == null) { json(x, 404, err("Student not found")); return; }
        Set<String> want = words(s.program());
        List<Match> out = new ArrayList<>();
        for (Company c : companies) {
            long free = c.capacity() - filled(c.id());
            if (free <= 0) continue;
            Set<String> hit = words(c.industry() + " " + c.focus()); hit.retainAll(want);
            int score = (int) Math.min(99, 40L * hit.size() + 6 * Math.min(free, 5) + 10);
            out.add(new Match(c, score, hit.isEmpty() ? free + " open slot(s)" : "Matches: " + String.join(", ", hit)));
        }
        out.sort((a, b) -> b.score() - a.score());
        json(x, 200, arr(out.stream().limit(3), m -> "{\"id\":" + q(m.c().id()) + ",\"name\":" + q(m.c().name()) + ",\"score\":" + m.score() + ",\"why\":" + q(m.why()) + "}"));
    }

    // ---------- persistence: every table is described once, and only changed tables are rewritten ----------
    static boolean useDb = false;
    static final String CONNECTION_STRING = env("DATABASE_URL",
        env("DB_URL", "jdbc:mariadb://localhost:3306/palm_portal"));
    static final boolean PG = CONNECTION_STRING.startsWith("jdbc:postgresql:")
        || CONNECTION_STRING.startsWith("postgresql://") || CONNECTION_STRING.startsWith("postgres://");
    static final String DB_URL = jdbcUrl(CONNECTION_STRING);
    static final Properties DB_PROPERTIES = connectionProperties(CONNECTION_STRING);

    static final class Tbl {
        final String name, cols, order; final Supplier<List<Object[]>> rows; final Consumer<String[]> loader; String last = "";
        Tbl(String name, String cols, String order, Supplier<List<Object[]>> rows, Consumer<String[]> loader) {
            this.name = name; this.rows = rows; this.loader = loader;
            this.cols = quoteIdentifiers(cols);
            this.order = quoteIdentifiers(order);
        }
    }
    static String d(String s) { return s == null || s.isEmpty() ? null : s; }   // an empty date is stored as NULL
    static Double nullableDouble(String s) { return s == null || s.isEmpty() ? null : Double.valueOf(s); }
    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    static String quoteIdentifiers(String names) {
        String quote = PG ? "\"" : "`";
        return Arrays.stream(names.split(",")).map(c -> quote + c + quote).collect(Collectors.joining(","));
    }
    static String jdbcUrl(String connectionString) {
        if (connectionString.startsWith("postgresql://") || connectionString.startsWith("postgres://")) {
            URI uri = URI.create(connectionString);
            String authority = uri.getRawAuthority();
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            return "jdbc:postgresql://" + authority + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        }
        return connectionString;
    }
    static Properties connectionProperties(String connectionString) {
        Properties properties = new Properties();
        if (connectionString.startsWith("postgresql://") || connectionString.startsWith("postgres://")) {
            URI uri = URI.create(connectionString);
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                if (separator < 1) throw new IllegalArgumentException("DATABASE_URL must include both a username and password.");
                properties.setProperty("user", userInfo.substring(0, separator));
                properties.setProperty("password", userInfo.substring(separator + 1));
            }
        } else if (PG) {
            String user = env("DB_USER"), pass = env("DB_PASS");
            if (user != null && !user.isBlank()) properties.setProperty("user", user);
            if (pass != null) properties.setProperty("password", pass);
        } else {
            properties.setProperty("user", env("DB_USER", "root"));
            properties.setProperty("password", env("DB_PASS", ""));
        }
        return properties;
    }

    static final List<Tbl> TABLES = List.of(
        new Tbl("students", "id,name,program,email", "id",
            () -> students.stream().map(s -> new Object[]{s.id(), s.name(), s.program(), s.email()}).toList(),
            r -> students.add(new Student(r[0], r[1], r[2], r[3]))),
        new Tbl("companies", "id,name,industry,focus,capacity,email,website,about,location_lat,location_lng,location_address,maps_link,location_set_on,location_set_by", "id",
            () -> companies.stream().map(c -> new Object[]{c.id(), c.name(), c.industry(), c.focus(), c.capacity(), c.email(), c.website(), c.about(),
                c.locationLat(), c.locationLng(), c.locationAddress(), c.mapsLink(), d(c.locationSetOn().replace('T', ' ')), c.locationSetBy()}).toList(),
            r -> companies.add(new Company(r[0], r[1], r[2], r[3], Integer.parseInt(r[4]), r[5], r[6], r[7],
                nullableDouble(r[8]), nullableDouble(r[9]), r[10], r[11], r[12].isEmpty() ? "" : iso(r[12]), r[13]))),
        new Tbl("users", "username,role,ref,salt,pass_hash,must_onboard,password_changed", "username",
            () -> users.values().stream().filter(u -> !u.name().equals("admin")).map(u -> new Object[]{u.name(), u.role(), u.ref(), u.salt(), u.hash(), u.mustOnboard() ? 1 : 0, u.passwordChanged() ? 1 : 0}).toList(),
            r -> users.put(r[0], new User(r[0], r[1], r[2], Base64.getDecoder().decode(r[3]), Base64.getDecoder().decode(r[4]),
                r[5].equals("1") || r[5].equalsIgnoreCase("true"), r[6].equals("1") || r[6].equalsIgnoreCase("true")))),
        new Tbl("unverified", "username", "username", () -> unverified.stream().map(u -> new Object[]{u}).toList(), r -> unverified.add(r[0])),
        new Tbl("applications", "id,student_id,company_id,pref,statement,status,updated_on,school_note,company_note", "id",
            () -> apps.stream().map(a -> new Object[]{a.id, a.sid, a.cid, a.pref, a.statement, a.status, a.date, a.schoolNote, a.companyNote}).toList(),
            r -> { App a = new App(r[0], r[1], r[2], r[4], r[5], r[6]); a.pref = r[3]; a.schoolNote = r[7]; a.companyNote = r[8]; apps.add(a); }),
        new Tbl("placements", "student_id,company_id,status,placed_on,start_date,end_date", "id",
            () -> placements.stream().map(p -> new Object[]{p.sid, p.cid, p.status, p.date, d(p.start), d(p.end)}).toList(),
            r -> { Placement p = new Placement(r[0], r[1], r[2], r[3]); p.start = r[4]; p.end = r[5]; placements.add(p); }),
        new Tbl("attendance", "student_id,company_id,clock_in,clock_out,in_lat,in_lng,in_accuracy,in_distance,in_verified,out_lat,out_lng,out_accuracy,out_distance", "id",
            () -> attendance.stream().map(w -> new Object[]{w.sid(), w.cid(), w.clockIn().replace('T', ' '), d(w.clockOut().replace('T', ' ')),
                w.inLat(), w.inLng(), w.inAccuracy(), w.inDistance(), w.inVerified() ? 1 : 0, w.outLat(), w.outLng(), w.outAccuracy(), w.outDistance()}).toList(),
            r -> attendance.add(new Att(r[0], r[1], iso(r[2]), r[3].isEmpty() ? "" : iso(r[3]), nullableDouble(r[4]), nullableDouble(r[5]),
                nullableDouble(r[6]), nullableDouble(r[7]), r[8].equals("1") || r[8].equalsIgnoreCase("true"), nullableDouble(r[9]), nullableDouble(r[10]), nullableDouble(r[11]), nullableDouble(r[12])))),
        new Tbl("reports", "student_id,company_id,summary,reported_on", "student_id,company_id",
            () -> reports.stream().map(k -> new Object[]{k.sid(), k.cid(), k.summary(), k.date()}).toList(),
            r -> reports.add(new Rep(r[0], r[1], r[2], r[3]))),
        new Tbl("posts", "id,company_id,type,title,body,posted_on", "id",
            () -> posts.stream().map(p -> new Object[]{p.id(), p.cid(), p.type(), p.title(), p.body(), p.date()}).toList(),
            r -> posts.add(new Post(r[0], r[1], r[2], r[3], r[4], r[5]))),
        new Tbl("logbook", "id,student_id,company_id,week,body,status,note,created_on", "id",
            () -> logs.stream().map(l -> new Object[]{l.id(), l.sid(), l.cid(), l.week(), l.body(), l.status(), l.note(), l.date()}).toList(),
            r -> logs.add(new LogEntry(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]))),
        new Tbl("evaluations", "student_id,company_id,punctuality,skills,attitude,teamwork,comment,created_on", "student_id,company_id",
            () -> evals.stream().map(v -> new Object[]{v.sid(), v.cid(), v.punctuality(), v.skills(), v.attitude(), v.teamwork(), v.comment(), v.date()}).toList(),
            r -> evals.add(new Eval(r[0], r[1], Integer.parseInt(r[2]), Integer.parseInt(r[3]), Integer.parseInt(r[4]), Integer.parseInt(r[5]), r[6], r[7]))),
        new Tbl("evaluation_forms", "id,title,status,created_on", "id",
            () -> evaluationForms.stream().map(f -> new Object[]{f.id(), f.title(), f.status(), f.createdOn()}).toList(),
            r -> evaluationForms.add(new EvaluationForm(r[0], r[1], r[2], r[3]))),
        new Tbl("evaluation_questions", "id,form_id,prompt,question_type,is_required,sort_order,options_text", "id",
            () -> evaluationQuestions.stream().map(q -> new Object[]{q.id(), q.formId(), q.prompt(), q.type(), q.required() ? 1 : 0, q.order(), q.options()}).toList(),
            r -> evaluationQuestions.add(new EvaluationQuestion(r[0], r[1], r[2], r[3], r[4].equals("1") || r[4].equalsIgnoreCase("true"), Integer.parseInt(r[5]), r[6]))),
        new Tbl("evaluation_responses", "student_id,company_id,form_id,answers,submitted_on", "student_id,company_id",
            () -> evaluationResponses.stream().map(r -> new Object[]{r.sid(), r.cid(), r.formId(), r.answers(), r.submittedOn().replace('T', ' ')}).toList(),
            r -> evaluationResponses.add(new EvaluationResponse(r[0], r[1], r[2], r[3], iso(r[4])))),
        new Tbl("notifications", "username,created_at,text,is_read", "id",
            () -> notes.stream().map(n -> new Object[]{n.user(), n.at().replace('T', ' '), n.text(), n.read() ? 1 : 0}).toList(),
            r -> notes.add(new Note(r[0], iso(r[1]), r[2], r[3].equals("1") || r[3].equalsIgnoreCase("true")))),
        new Tbl("audit_log", "created_at,actor,action,detail", "id",
            () -> audits.stream().map(a -> new Object[]{a.at().replace('T', ' '), a.actor(), a.action(), a.detail()}).toList(),
            r -> audits.add(new Aud(iso(r[0]), r[1], r[2], r[3]))),
        new Tbl("settings", "k,v", "k", () -> settings.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toList(), r -> settings.put(r[0], r[1])),
        new Tbl("student_register", "email,name", "email", () -> register.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toList(), r -> register.put(r[0], r[1])),
        new Tbl("access_requests", "id,name,program,email,reason,created_at,status", "id",
            () -> accessRequests.stream().map(a -> new Object[]{a.id(), a.name(), a.program(), a.email(), a.reason(), a.date().replace('T', ' '), a.status()}).toList(),
            r -> accessRequests.add(new AccessRequest(r[0], r[1], r[2], r[3], r[4], iso(r[5]), r[6]))));

    static String fingerprint(List<Object[]> rows) { String s = rows.stream().map(Arrays::deepToString).collect(Collectors.joining("\n")); return s.length() + ":" + s.hashCode(); }
    static Connection conn() throws SQLException {
        boolean hosted = !DB_URL.matches("(?i)^jdbc:(postgresql|mariadb)://(localhost|127\\.0\\.0\\.1|\\[?::1\\]?)(:\\d+)?/.*");
        int attempts = hosted ? 3 : 1;
        SQLException failure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return DB_PROPERTIES.isEmpty()
                    ? DriverManager.getConnection(DB_URL)
                    : DriverManager.getConnection(DB_URL, DB_PROPERTIES);
            } catch (SQLException e) {
                failure = e;
                if (attempt < attempts) {
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted while retrying the database connection.", interrupted);
                    }
                }
            }
        }
        throw failure;
    }

    static void clearAll() {
        students.clear(); companies.clear(); placements.clear(); apps.clear(); attendance.clear(); reports.clear(); posts.clear(); logs.clear(); evals.clear();
        evaluationForms.clear(); evaluationQuestions.clear(); evaluationResponses.clear();
        notes.clear(); audits.clear(); settings.clear(); register.clear(); accessRequests.clear(); unverified.clear(); users.values().removeIf(u -> !u.name().equals("admin"));
    }

    static void load() throws IOException {
        try (Connection c = conn(); var st = c.createStatement()) {
            for (Tbl t : TABLES) {
                String table = (PG ? "\"" : "`") + t.name + (PG ? "\"" : "`");
                var rs = st.executeQuery("SELECT " + t.cols + " FROM " + table + " ORDER BY " + t.order);
                int n = rs.getMetaData().getColumnCount(); int[] ty = new int[n];
                for (int i = 0; i < n; i++) ty[i] = rs.getMetaData().getColumnType(i + 1);
                while (rs.next()) {
                    String[] r = new String[n];
                    for (int i = 0; i < n; i++) {
                        if (ty[i] == Types.VARBINARY || ty[i] == Types.BINARY || ty[i] == Types.LONGVARBINARY) { byte[] b = rs.getBytes(i + 1); r[i] = b == null ? "" : b64(b); }
                        else { String v = rs.getString(i + 1); r[i] = v == null ? "" : v; }
                    }
                    t.loader.accept(r);
                }
                t.last = fingerprint(t.rows.get());
            }
            useDb = true;
            System.out.println((PG ? "PostgreSQL" : "MariaDB/MySQL") + " database connected.");
        } catch (SQLException e) {
            clearAll();
            System.err.println("DATABASE UNAVAILABLE: " + e.getMessage());
            if (!"true".equalsIgnoreCase(env("ALLOW_FILE_MODE"))) {
                System.err.println("Configure DATABASE_URL (or DB_URL for MariaDB/MySQL), run the matching schema, and start with the required JDBC driver. (Throw-away demos only: set ALLOW_FILE_MODE=true.)");
                System.exit(1);
            }
            System.out.println("ALLOW_FILE_MODE is on: using data.tsv. Do not use this for real data.");
            loadFile();
        }
    }

    static synchronized void save() {
        if (!useDb) { saveFile(); return; }
        Map<Tbl, List<Object[]>> changed = new LinkedHashMap<>(); Map<Tbl, String> fps = new HashMap<>();
        for (Tbl t : TABLES) { List<Object[]> rs = t.rows.get(); String fp = fingerprint(rs); if (!fp.equals(t.last)) { changed.put(t, rs); fps.put(t, fp); } }
        if (changed.isEmpty()) return;
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                try (var st = c.createStatement()) {
                    if (!PG) st.execute("SET FOREIGN_KEY_CHECKS=0");
                    for (Tbl t : changed.keySet()) {
                        String table = (PG ? "\"" : "`") + t.name + (PG ? "\"" : "`");
                        st.executeUpdate("DELETE FROM " + table);
                    }
                }
                for (var en : changed.entrySet()) {
                    Tbl t = en.getKey(); if (en.getValue().isEmpty()) continue;
                    int n = t.cols.split(",").length;
                    String table = (PG ? "\"" : "`") + t.name + (PG ? "\"" : "`");
                    try (var ps = c.prepareStatement("INSERT INTO " + table + " (" + t.cols + ") VALUES (" + "?,".repeat(n - 1) + "?)")) {
                        for (Object[] row : en.getValue()) {
                            for (int i = 0; i < n; i++) {
                                if (row[i] == null) ps.setNull(i + 1, Types.NULL);
                                else if (PG && row[i] instanceof String) ps.setObject(i + 1, row[i], Types.OTHER);
                                else ps.setObject(i + 1, row[i]);
                            }
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                c.commit();
                if (!PG) try (var st = c.createStatement()) { st.execute("SET FOREIGN_KEY_CHECKS=1"); }
                fps.forEach((t, fp) -> t.last = fp);
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { System.err.println("Database save failed: " + e.getMessage()); }
    }

    // ---------- data.tsv: only for ALLOW_FILE_MODE demos ----------
    static void saveFile() {
        List<String> L = new ArrayList<>();
        for (Tbl t : TABLES) for (Object[] row : t.rows.get()) {
            StringBuilder sb = new StringBuilder(t.name);
            for (Object o : row) sb.append('\t').append(o == null ? "" : o instanceof byte[] b ? b64(b) : String.valueOf(o).replace('\t', ' ').replace('\n', ' '));
            L.add(sb.toString());
        }
        try { Files.write(DB, L); } catch (IOException e) { System.err.println("Save failed: " + e.getMessage()); }
    }
    static void loadFile() throws IOException {
        if (!Files.exists(DB)) return;
        Map<String, Tbl> by = new HashMap<>();
        for (Tbl t : TABLES) by.put(t.name, t);
        for (String l : Files.readAllLines(DB)) {
            String[] t = l.split("\t", -1); Tbl tb = by.get(t[0]);
            if (tb != null) tb.loader.accept(Arrays.copyOfRange(t, 1, t.length));
        }
    }

    static void importDataFile() throws IOException {
        if (!useDb) throw new IOException("IMPORT_DATA_FILE requires a connected SQL database.");
        if (TABLES.stream().anyMatch(t -> !t.rows.get().isEmpty())) {
            throw new IOException("IMPORT_DATA_FILE only works when every database table is empty.");
        }
        if (!Files.exists(DB)) throw new IOException("IMPORT_DATA_FILE is set, but " + DB + " was not found.");
        if (Files.size(DB) == 0) throw new IOException("IMPORT_DATA_FILE is set, but the transfer file is empty.");
        Set<String> tableNames = TABLES.stream().map(t -> t.name).collect(Collectors.toSet());
        int rows = 0;
        for (String line : Files.readAllLines(DB)) {
            if (line.isBlank()) continue;
            String table = line.split("\t", 2)[0];
            if (!tableNames.contains(table)) throw new IOException("Transfer file contains an unknown table name: " + table);
            rows++;
        }
        if (rows == 0) throw new IOException("IMPORT_DATA_FILE is set, but the transfer file contains no database rows.");
        loadFile();
        save();
        if (TABLES.stream().anyMatch(t -> !t.last.equals(fingerprint(t.rows.get())))) {
            throw new IOException("Database import failed; see the database save error above.");
        }
        System.out.println("Imported " + DB + " into the database.");
    }
}