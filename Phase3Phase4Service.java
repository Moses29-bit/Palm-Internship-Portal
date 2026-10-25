import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Phase 3 + 4 service layer for the Phase 2A normalized PostgreSQL schema.
 * This class deliberately uses the redesigned schema; it must not be pointed at
 * the legacy compatibility schema from Phase 2C.
 * Java 17+, PostgreSQL JDBC driver. No ORM is required.
 */
public final class Phase3Phase4Service {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int HASH_BITS = 256;

    public Phase3Phase4Service() {}

    private Connection connect() throws SQLException {
        return Database.getConnection();
    }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid identifier."); } }
    private static String required(String value, String label, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > max) throw new IllegalArgumentException(label + " is required and must be at most " + max + " characters.");
        return v;
    }
    private static String email(String value) {
        String e = required(value, "Email", 320).toLowerCase(Locale.ROOT);
        if (!e.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) throw new IllegalArgumentException("Enter a valid email address.");
        return e;
    }
    private static void requireRole(Connection c, UUID userId, String... allowed) throws SQLException {
        String sql = "SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) for (String role : allowed) if (role.equals(rs.getString(1))) return;
            }
        }
        throw new SecurityException("Your account is not permitted to perform this action.");
    }
    private static void requireActiveUser(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status::text FROM users WHERE id=?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"ACTIVE".equals(rs.getString(1))) throw new SecurityException("An active signed-in account is required.");
            }
        }
    }
    private static void audit(Connection c, UUID actor, String action, String entityType, UUID entityId, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_logs(actor_user_id,action,entity_type,entity_id,details,created_at) VALUES (?,?,?,?,to_jsonb(?::text),NOW())")) {
            ps.setObject(1, actor); ps.setString(2, action); ps.setString(3, entityType); ps.setObject(4, entityId); ps.setString(5, detail == null ? "" : detail); ps.executeUpdate();
        }
    }
    private static String hashPassword(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, HASH_BITS);
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        finally { spec.clearPassword(); Arrays.fill(password, '\0'); }
    }
    private static byte[] newSalt() { byte[] b = new byte[16]; RNG.nextBytes(b); return b; }
    private static String sha256(String value) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Creates a short-lived email code. The caller must deliver it through the configured email provider. */
    public String createEmailVerificationChallenge(UUID userId) throws Exception {
        String code = String.format(Locale.ROOT, "%06d", RNG.nextInt(1_000_000));
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("SELECT status::text FROM users WHERE id=? FOR UPDATE")) {
                    ps.setObject(1, userId); try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !"PENDING".equals(rs.getString(1))) throw new IllegalArgumentException("No pending account requires verification.");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE email_verification_challenges SET consumed_at=NOW() WHERE user_id=? AND consumed_at IS NULL")) { ps.setObject(1,userId); ps.executeUpdate(); }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO email_verification_challenges(user_id,code_hash,expires_at) VALUES (?,?,NOW()+INTERVAL '30 minutes')")) { ps.setObject(1,userId); ps.setString(2,sha256(code)); ps.executeUpdate(); }
                c.commit(); return code;
            } catch (Exception ex) { c.rollback(); throw ex; } finally { c.setAutoCommit(true); }
        }
    }

    /** Verifies a one-time code and activates the account. Add request/IP throttling at the HTTP boundary too. */
    public void verifyEmailChallenge(String emailAddress, String code) throws Exception {
        String em = email(emailAddress);
        String supplied = required(code, "Verification code", 6);
        if (!supplied.matches("\\d{6}")) throw new IllegalArgumentException("Invalid or expired verification code.");
        boolean verified = false;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                UUID userId;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM users WHERE lower(email)=? AND status='PENDING' FOR UPDATE")) {
                    ps.setString(1, em);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            throw new IllegalArgumentException("Invalid or expired verification code.");
                        }
                        userId = rs.getObject(1, UUID.class);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id,code_hash FROM email_verification_challenges " +
                        "WHERE user_id=? AND consumed_at IS NULL AND expires_at>NOW() AND attempts<5 " +
                        "ORDER BY created_at DESC LIMIT 1 FOR UPDATE")) {
                    ps.setObject(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            UUID challengeId = rs.getObject("id", UUID.class);
                            String actualHash = sha256(supplied);
                            verified = MessageDigest.isEqual(
                                actualHash.getBytes(StandardCharsets.UTF_8),
                                rs.getString("code_hash").getBytes(StandardCharsets.UTF_8)
                            );
                            try (PreparedStatement update = c.prepareStatement(
                                    "UPDATE email_verification_challenges " +
                                    "SET attempts=attempts+1,consumed_at=CASE WHEN ? THEN NOW() ELSE consumed_at END " +
                                    "WHERE id=?")) {
                                update.setBoolean(1, verified);
                                update.setObject(2, challengeId);
                                update.executeUpdate();
                            }
                        }
                    }
                }

                if (verified) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE users SET email_verified_at=NOW(),status='ACTIVE'," +
                            "failed_login_attempts=0,locked_until=NULL WHERE id=?")) {
                        ps.setObject(1, userId);
                        ps.executeUpdate();
                    }
                    audit(c, userId, "EMAIL_VERIFIED", "user", userId,
                        "Institutional email verification completed.");
                }
                c.commit();
            } catch (Exception ex) {
                if (!c.getAutoCommit()) c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        }
        if (!verified) throw new IllegalArgumentException("Invalid or expired verification code.");
    }

    /** Returns user ID only after email verification and activation; HTTP layer must create a secure session. */
    public UUID authenticate(String emailAddress, String password) throws Exception {
        String em = email(emailAddress);
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Invalid email or password.");
        }
        try(Connection c=connect()){c.setAutoCommit(false);try{
            UUID uid=null;String encoded=null,status=null;Timestamp locked=null;
            try(PreparedStatement ps=c.prepareStatement("SELECT id,password_hash,status::text,locked_until FROM users WHERE lower(email)=? FOR UPDATE")){ps.setString(1,em);try(ResultSet rs=ps.executeQuery()){if(rs.next()){uid=rs.getObject(1,UUID.class);encoded=rs.getString(2);status=rs.getString(3);locked=rs.getTimestamp(4);}}}
            if(uid==null) { c.rollback(); throw new IllegalArgumentException("Invalid email or password."); }
            if(locked!=null&&locked.toInstant().isAfter(java.time.Instant.now())) { c.rollback(); throw new IllegalArgumentException("Sign-in is temporarily locked. Try again later."); }
            boolean match = false;
            String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
            if (parts.length == 4 && "pbkdf2".equals(parts[0])) {
                try {
                    int rounds = Integer.parseInt(parts[1]);
                    byte[] salt = Base64.getDecoder().decode(parts[2]);
                    if (rounds == PBKDF2_ITERATIONS) {
                        String actual = hashPassword(password.toCharArray(), salt);
                        match = MessageDigest.isEqual(
                            actual.getBytes(StandardCharsets.UTF_8),
                            parts[3].getBytes(StandardCharsets.UTF_8)
                        );
                    }
                } catch (IllegalArgumentException ex) {
                    match = false;
                }
            }
            if(!match){try(PreparedStatement ps=c.prepareStatement("UPDATE users SET failed_login_attempts=failed_login_attempts+1,locked_until=CASE WHEN failed_login_attempts+1>=5 THEN NOW()+INTERVAL '15 minutes' ELSE locked_until END WHERE id=?")){ps.setObject(1,uid);ps.executeUpdate();}c.commit();throw new IllegalArgumentException("Invalid email or password.");}
            if(!"ACTIVE".equals(status)){c.rollback();throw new IllegalArgumentException("Please verify your email or contact the Internship Office before signing in.");}
            try(PreparedStatement ps=c.prepareStatement("UPDATE users SET failed_login_attempts=0,locked_until=NULL,last_login_at=NOW() WHERE id=?")){ps.setObject(1,uid);ps.executeUpdate();}
            c.commit();return uid;
        }catch(Exception ex){if(!c.getAutoCommit())try{c.rollback();}catch(SQLException ignored){}throw ex;}finally{c.setAutoCommit(true);}}
    }
    private static boolean strongPassword(String p) {
        return p != null && p.length() >= 12 && p.length() <= 128 && p.chars().anyMatch(Character::isUpperCase)
            && p.chars().anyMatch(Character::isLowerCase) && p.chars().anyMatch(Character::isDigit)
            && p.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
    }

    /** Registration only succeeds when the active cycle has a matching imported eligibility record. */
    public UUID registerEligibleStudent(String cycleId, String studentNumber, String fullName,
                                        String institutionalEmail, String programmeCode, String level,
                                        String password) throws Exception {
        UUID cycle = uuid(cycleId); String sn = required(studentNumber, "Student number", 80);
        String name = required(fullName, "Full name", 250); String em = email(institutionalEmail);
        String prog = required(programmeCode, "Programme code", 50); String lev = level == null ? "" : level.trim();
        if (!strongPassword(password)) throw new IllegalArgumentException("Password must be 12–128 characters and include uppercase, lowercase, number and symbol.");
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("SELECT e.id,e.student_id,e.full_name,e.institutional_email,e.status::text,s.programme_id,p.code FROM student_eligibility e LEFT JOIN students s ON s.id=e.student_id LEFT JOIN programmes p ON p.id=e.programme_id JOIN internship_cycles ic ON ic.id=e.cycle_id WHERE e.cycle_id=? AND lower(e.institutional_email)=? AND e.student_number=? AND ic.status='OPEN' FOR UPDATE OF e")) {
                    ps.setObject(1, cycle); ps.setString(2, em); ps.setString(3, sn);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !Set.of("ELIGIBLE", "INVITED").contains(rs.getString("status")))
                            throw new IllegalArgumentException("We could not verify your details for the current internship cycle. Check your details or submit an access request.");
                        if (rs.getString("code") == null || !rs.getString("code").equalsIgnoreCase(prog)) throw new IllegalArgumentException("We could not verify your details for the current internship cycle. Check your details or submit an access request.");
                        if (!rs.getString("full_name").trim().equalsIgnoreCase(name)) throw new IllegalArgumentException("We could not verify your details for the current internship cycle. Check your details or submit an access request.");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE lower(email)=? OR lower(username)=?")) {
                    ps.setString(1, em); ps.setString(2, em); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) throw new IllegalArgumentException("An account could not be created with those details. Please sign in or contact the Internship Office."); }
                }
                byte[] salt = newSalt(); String passHash = hashPassword(password.toCharArray(), salt);
                UUID userId;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(username,email,password_hash,password_algorithm,status,email_verified_at) VALUES (?,?,?,'PBKDF2-SHA256','PENDING',NULL) RETURNING id")) {
                    ps.setString(1, em); ps.setString(2, em); ps.setString(3, "pbkdf2$" + PBKDF2_ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + passHash);
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); userId = rs.getObject(1, UUID.class); }
                }
                UUID studentId;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO students(user_id,student_number,full_name,institutional_email,programme_id,level,active) SELECT ?,?,?,?,p.id,?,TRUE FROM programmes p WHERE p.code=? AND p.active=TRUE RETURNING id")) {
                    ps.setObject(1, userId); ps.setString(2, sn); ps.setString(3, name); ps.setString(4, em); ps.setString(5, lev); ps.setString(6, prog);
                    try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("The programme code is not active or does not exist."); studentId = rs.getObject(1, UUID.class); }
                }
                assignRole(c, userId, "STUDENT");
                try (PreparedStatement ps = c.prepareStatement("UPDATE student_eligibility SET student_id=?,status='REGISTERED',updated_at=NOW() WHERE cycle_id=? AND student_number=?")) {
                    ps.setObject(1, studentId); ps.setObject(2, cycle); ps.setString(3, sn); ps.executeUpdate();
                }
                audit(c, userId, "STUDENT_REGISTERED", "student", studentId, "Registered against an existing eligibility record.");
                c.commit(); return userId;
            } catch (Exception ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    private static void assignRole(Connection c, UUID userId, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=? ON CONFLICT DO NOTHING")) {
            ps.setObject(1, userId); ps.setString(2, role); if (ps.executeUpdate() == 0) {
                try (PreparedStatement chk = c.prepareStatement("SELECT 1 FROM roles WHERE code=?")) { chk.setString(1, role); try (ResultSet rs = chk.executeQuery()) { if (!rs.next()) throw new SQLException("Required role is missing from roles table: " + role); } }
            }
        }
    }

    /** Public access request; it never creates a user or adds eligibility by itself. */
    public UUID requestStudentAccess(String cycleId, String studentNumber, String fullName, String institutionalEmail,
                                     String programmeText, String level, String reason) throws Exception {
        UUID cycle = uuid(cycleId); String sn = studentNumber == null ? "" : studentNumber.trim();
        String name = required(fullName, "Full name", 250); String em = email(institutionalEmail);
        String prog = programmeText == null ? "" : programmeText.trim(); String lev = level == null ? "" : level.trim();
        String why = reason == null ? "" : reason.trim(); if (why.length() > 2000) throw new IllegalArgumentException("Reason is too long.");
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("INSERT INTO access_requests(cycle_id,student_number,full_name,institutional_email,programme_text,level,reason,status) SELECT ?,?,?,?,?,?,?,'PENDING' WHERE EXISTS (SELECT 1 FROM internship_cycles WHERE id=? AND status IN ('OPEN','UPCOMING')) RETURNING id")) {
            ps.setObject(1, cycle); ps.setString(2, sn); ps.setString(3, name); ps.setString(4, em); ps.setString(5, prog); ps.setString(6, lev); ps.setString(7, why); ps.setObject(8, cycle);
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Access requests are not open for that internship cycle."); return rs.getObject(1, UUID.class); }
        }
    }

    /** Coordinator review. Approval creates an eligibility row; it does not create an account. */
    public void reviewAccessRequest(String requestId, UUID reviewer, boolean approve, String note, String programmeCode) throws Exception {
        UUID rid = uuid(requestId); String reviewNote = note == null ? "" : note.trim(); if (reviewNote.length() > 2000) throw new IllegalArgumentException("Review note is too long.");
        try (Connection c = connect()) { c.setAutoCommit(false); try {
            requireActiveUser(c, reviewer); requireRole(c, reviewer, "SUPER_ADMIN", "INTERNSHIP_COORDINATOR", "PLACEMENT_OFFICER");
            UUID cycle; String sn, name, em, level, progText;
            try (PreparedStatement ps = c.prepareStatement("SELECT cycle_id,student_number,full_name,institutional_email,level,programme_text,status::text FROM access_requests WHERE id=? FOR UPDATE")) {
                ps.setObject(1, rid); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Access request not found.");
                    cycle=rs.getObject(1,UUID.class); sn=rs.getString(2); name=rs.getString(3); em=rs.getString(4); level=rs.getString(5); progText=rs.getString(6);
                    if (!"PENDING".equals(rs.getString(7))) throw new IllegalArgumentException("This request has already been reviewed."); }
            }
            if (approve) {
                if (sn == null || sn.isBlank()) throw new IllegalArgumentException("A student number is required before approving this request.");
                UUID programmeId = null;
                String code = required(programmeCode, "Programme code", 50);
                if (!code.isEmpty()) try (PreparedStatement ps = c.prepareStatement("SELECT id FROM programmes WHERE code=? AND active=TRUE")) { ps.setString(1, code); try (ResultSet rs=ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Selected programme does not exist or is inactive."); programmeId=rs.getObject(1,UUID.class); } }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO student_eligibility(cycle_id,student_number,full_name,institutional_email,programme_id,level,status,source,imported_by) VALUES (?,?,?,?,?,?,'ELIGIBLE','ACCESS_REQUEST',?) ON CONFLICT (cycle_id,student_number) DO UPDATE SET full_name=EXCLUDED.full_name,institutional_email=EXCLUDED.institutional_email,programme_id=EXCLUDED.programme_id,level=EXCLUDED.level,status='ELIGIBLE',source='ACCESS_REQUEST',imported_by=EXCLUDED.imported_by,updated_at=NOW()")) {
                    ps.setObject(1,cycle); ps.setString(2,sn == null ? "" : sn); ps.setString(3,name); ps.setString(4,em); ps.setObject(5,programmeId); ps.setString(6,level); ps.setObject(7,reviewer); ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE access_requests SET status=?::access_request_status,reviewed_by=?,reviewed_at=NOW(),review_note=?,updated_at=NOW() WHERE id=?")) {
                ps.setString(1, approve ? "APPROVED" : "REJECTED"); ps.setObject(2, reviewer); ps.setString(3, reviewNote); ps.setObject(4,rid); ps.executeUpdate();
            }
            audit(c, reviewer, approve ? "ACCESS_REQUEST_APPROVED" : "ACCESS_REQUEST_REJECTED", "access_request", rid, reviewNote); c.commit();
        } catch (Exception ex) { c.rollback(); throw ex; } finally { c.setAutoCommit(true); } }
    }

    public UUID createCompany(UUID actor, String name, String industry, String emailAddress, String phone, String website, String about, int capacity) throws Exception {
        if (capacity < 0) throw new IllegalArgumentException("Capacity cannot be negative.");
        String n=required(name,"Company name",250); String ind=industry==null?"":industry.trim(); String em=emailAddress==null||emailAddress.isBlank()?null:email(emailAddress);
        try (Connection c=connect()) { c.setAutoCommit(false); try { requireActiveUser(c,actor); requireRole(c,actor,"SUPER_ADMIN","INTERNSHIP_COORDINATOR","PLACEMENT_OFFICER","DEPARTMENT_COORDINATOR");
            UUID id; try (PreparedStatement ps=c.prepareStatement("INSERT INTO companies(name,industry,email,phone,website,about,capacity,status) VALUES (?,?,?,?,?,?,?,'PENDING') RETURNING id")) {
                ps.setString(1,n); ps.setString(2,ind); ps.setString(3,em); ps.setString(4,phone); ps.setString(5,website); ps.setString(6,about); ps.setInt(7,capacity); try(ResultSet rs=ps.executeQuery()){rs.next();id=rs.getObject(1,UUID.class);}}
            audit(c,actor,"COMPANY_CREATED","company",id,"Company requires verification before its opportunities can be opened."); c.commit(); return id;
        } catch(Exception ex){c.rollback();throw ex;} finally{c.setAutoCommit(true);} }
    }

    public void reviewCompany(UUID actor, String companyId, String decision) throws Exception {
        UUID cid=uuid(companyId); String d=required(decision,"Decision",20).toUpperCase(Locale.ROOT);
        if (!Set.of("UNDER_REVIEW","VERIFIED","ACTIVE","SUSPENDED","INACTIVE","REJECTED").contains(d)) throw new IllegalArgumentException("Unsupported company decision.");
        try(Connection c=connect()){c.setAutoCommit(false);try{requireActiveUser(c,actor);requireRole(c,actor,"SUPER_ADMIN","INTERNSHIP_COORDINATOR","PLACEMENT_OFFICER");
            try(PreparedStatement ps=c.prepareStatement("UPDATE companies SET status=?::company_status,verified_by=CASE WHEN ? IN ('VERIFIED','ACTIVE') THEN ? ELSE verified_by END,verified_at=CASE WHEN ? IN ('VERIFIED','ACTIVE') THEN NOW() ELSE verified_at END,updated_at=NOW() WHERE id=?")){ps.setString(1,d);ps.setString(2,d);ps.setObject(3,actor);ps.setString(4,d);ps.setObject(5,cid);if(ps.executeUpdate()!=1)throw new IllegalArgumentException("Company not found.");}
            audit(c,actor,"COMPANY_STATUS_CHANGED","company",cid,d);c.commit();}catch(Exception ex){c.rollback();throw ex;}finally{c.setAutoCommit(true);}}
    }

    public UUID createOpportunity(UUID actor, String companyId, String cycleId, String title, String description,
                                 String requirements, String location, String workMode, int capacity,
                                 LocalDate deadline, boolean publishNow) throws Exception {
        UUID cid=uuid(companyId), cycle=uuid(cycleId); String t=required(title,"Opportunity title",250), desc=required(description,"Description",12000);
        if(capacity<1||capacity>500)throw new IllegalArgumentException("Capacity must be between 1 and 500.");
        try(Connection c=connect()){c.setAutoCommit(false);try{requireActiveUser(c,actor);requireRole(c,actor,"SUPER_ADMIN","INTERNSHIP_COORDINATOR","PLACEMENT_OFFICER","COMPANY_ADMIN");
            // A company user may only manage their own company.
            boolean admin=false; try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code IN ('SUPER_ADMIN','INTERNSHIP_COORDINATOR','PLACEMENT_OFFICER')")){ps.setObject(1,actor);try(ResultSet rs=ps.executeQuery()){admin=rs.next();}}
            if(!admin)try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM company_users WHERE user_id=? AND company_id=?")){ps.setObject(1,actor);ps.setObject(2,cid);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new SecurityException("You may only manage opportunities for your own company.");}}
            try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM companies WHERE id=? AND status IN ('VERIFIED','ACTIVE')")){ps.setObject(1,cid);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Company must be verified before publishing opportunities.");}}
            try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM internship_cycles WHERE id=? AND status IN ('UPCOMING','OPEN')")){ps.setObject(1,cycle);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Choose an upcoming or open internship cycle.");}}
            UUID id;try(PreparedStatement ps=c.prepareStatement("INSERT INTO opportunities(company_id,cycle_id,title,description,requirements,location,work_mode,capacity,application_deadline,status,created_by) VALUES (?,?,?,?,?,?,?,?,?,?::opportunity_status,?) RETURNING id")){ps.setObject(1,cid);ps.setObject(2,cycle);ps.setString(3,t);ps.setString(4,desc);ps.setString(5,requirements);ps.setString(6,location);ps.setString(7,workMode);ps.setInt(8,capacity);ps.setObject(9,deadline==null?null:deadline.atTime(23,59,59).atOffset(java.time.ZoneOffset.UTC));ps.setString(10,publishNow?"OPEN":"DRAFT");ps.setObject(11,actor);try(ResultSet rs=ps.executeQuery()){rs.next();id=rs.getObject(1,UUID.class);}}
            audit(c,actor,"OPPORTUNITY_CREATED","opportunity",id,publishNow?"OPEN":"DRAFT");c.commit();return id;
        }catch(Exception ex){c.rollback();throw ex;}finally{c.setAutoCommit(true);}}
    }

    public List<Map<String,Object>> listOpenOpportunities(String cycleId) throws Exception {
        UUID cycle=uuid(cycleId); List<Map<String,Object>> out=new ArrayList<>();
        String sql="SELECT o.id,o.title,o.description,o.requirements,o.location,o.work_mode,o.capacity,o.application_deadline,c.id company_id,c.name company_name,c.industry FROM opportunities o JOIN companies c ON c.id=o.company_id WHERE o.cycle_id=? AND o.status='OPEN' AND c.status IN ('VERIFIED','ACTIVE') AND (o.application_deadline IS NULL OR o.application_deadline>NOW()) ORDER BY o.created_at DESC";
        try(Connection c=connect();PreparedStatement ps=c.prepareStatement(sql)){ps.setObject(1,cycle);try(ResultSet rs=ps.executeQuery()){while(rs.next()){Map<String,Object> m=new LinkedHashMap<>();for(String k:List.of("id","title","description","requirements","location","work_mode","capacity","application_deadline","company_id","company_name","industry"))m.put(k,rs.getObject(k));out.add(m);}}}return out;
    }

    public UUID submitApplication(UUID actor, String cycleId, String opportunityId, String statement, Integer preferenceRank) throws Exception {
        UUID cycle=uuid(cycleId), oid=uuid(opportunityId);String st=required(statement,"Application statement",5000);
        if (preferenceRank != null && preferenceRank < 1) {
            throw new IllegalArgumentException("Preference rank must be positive.");
        }
        try(Connection c=connect()){c.setAutoCommit(false);try{requireActiveUser(c,actor);requireRole(c,actor,"STUDENT");
            UUID sid;try(PreparedStatement ps=c.prepareStatement("SELECT s.id FROM students s WHERE s.user_id=? AND s.active=TRUE FOR UPDATE")){ps.setObject(1,actor);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new SecurityException("No active student profile is linked to this account.");sid=rs.getObject(1,UUID.class);}}
            try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM student_eligibility e JOIN internship_cycles ic ON ic.id=e.cycle_id WHERE e.cycle_id=? AND e.student_id=? AND e.status IN ('REGISTERED','VERIFIED','ACTIVE') AND ic.status='OPEN' AND (ic.application_close IS NULL OR ic.application_close>NOW())")){ps.setObject(1,cycle);ps.setObject(2,sid);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("You are not eligible to apply in this open cycle, or the application deadline has passed.");}}
            UUID company;try(PreparedStatement ps=c.prepareStatement("SELECT o.company_id FROM opportunities o JOIN companies co ON co.id=o.company_id WHERE o.id=? AND o.cycle_id=? AND o.status='OPEN' AND co.status IN ('VERIFIED','ACTIVE') AND (o.application_deadline IS NULL OR o.application_deadline>NOW()) FOR UPDATE OF o")){ps.setObject(1,oid);ps.setObject(2,cycle);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("This opportunity is no longer accepting applications.");company=rs.getObject(1,UUID.class);}}
            try(PreparedStatement ps=c.prepareStatement("SELECT count(*) FROM applications WHERE cycle_id=? AND student_id=? AND status NOT IN ('REJECTED','WITHDRAWN','DECLINED','EXPIRED','CANCELLED')")){ps.setObject(1,cycle);ps.setObject(2,sid);try(ResultSet rs=ps.executeQuery()){rs.next();if(rs.getInt(1)>=3)throw new IllegalArgumentException("You have reached the maximum of three active applications for this cycle.");}}
            try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM applications WHERE cycle_id=? AND student_id=? AND opportunity_id=? AND status NOT IN ('REJECTED','WITHDRAWN','DECLINED','EXPIRED','CANCELLED')")){ps.setObject(1,cycle);ps.setObject(2,sid);ps.setObject(3,oid);try(ResultSet rs=ps.executeQuery()){if(rs.next())throw new IllegalArgumentException("You already have an active application for this opportunity.");}}
            UUID id;try(PreparedStatement ps=c.prepareStatement("INSERT INTO applications(cycle_id,student_id,company_id,opportunity_id,preference_rank,statement,status,submitted_at,updated_by) VALUES (?,?,?,?,?,?,'SUBMITTED',NOW(),?) RETURNING id")){ps.setObject(1,cycle);ps.setObject(2,sid);ps.setObject(3,company);ps.setObject(4,oid);if(preferenceRank==null)ps.setNull(5,Types.INTEGER);else ps.setInt(5,preferenceRank);ps.setString(6,st);ps.setObject(7,actor);try(ResultSet rs=ps.executeQuery()){rs.next();id=rs.getObject(1,UUID.class);}}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO application_status_history(application_id,old_status,new_status,changed_by,reason) VALUES (?,NULL,'SUBMITTED',?,'Student submitted application')")){ps.setObject(1,id);ps.setObject(2,actor);ps.executeUpdate();}
            audit(c,actor,"APPLICATION_SUBMITTED","application",id,"Submitted for opportunity " + oid);c.commit();return id;
        }catch(Exception ex){c.rollback();throw ex;}finally{c.setAutoCommit(true);}}
    }

    /** Role-aware application state transitions with a status-history record. */
    public void transitionApplication(UUID actor, String applicationId, String newStatus, String note) throws Exception {
        UUID aid=uuid(applicationId);String target=required(newStatus,"New status",40).toUpperCase(Locale.ROOT);String reason=note==null?"":note.trim();if(reason.length()>2000)throw new IllegalArgumentException("Note is too long.");
        Set<String> valid=Set.of("UNDER_REVIEW","SHORTLISTED","FORWARDED","COMPANY_REVIEW","INTERVIEW","ACCEPTED","REJECTED","WITHDRAWN","DECLINED","EXPIRED","CANCELLED");if(!valid.contains(target))throw new IllegalArgumentException("Unsupported application transition.");
        try(Connection c=connect()){c.setAutoCommit(false);try{requireActiveUser(c,actor);String old;UUID company;
            try(PreparedStatement ps=c.prepareStatement("SELECT a.status::text,a.company_id FROM applications a WHERE a.id=? FOR UPDATE")){ps.setObject(1,aid);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Application not found.");old=rs.getString(1);company=rs.getObject(2,UUID.class);}}
            boolean admin=false,companyUser=false;try(PreparedStatement ps=c.prepareStatement("SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?")){ps.setObject(1,actor);try(ResultSet rs=ps.executeQuery()){while(rs.next()){String role=rs.getString(1);if(Set.of("SUPER_ADMIN","INTERNSHIP_COORDINATOR","PLACEMENT_OFFICER","DEPARTMENT_COORDINATOR").contains(role))admin=true;if("COMPANY_ADMIN".equals(role)||"COMPANY_SUPERVISOR".equals(role))companyUser=true;}}}
            if(!admin && companyUser){try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM company_users WHERE user_id=? AND company_id=?")){ps.setObject(1,actor);ps.setObject(2,company);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new SecurityException("You cannot review this company's applications.");}} if(!Set.of("COMPANY_REVIEW","INTERVIEW","ACCEPTED","DECLINED").contains(target))throw new SecurityException("Company users cannot perform this transition.");}
            else if(!admin) { requireRole(c,actor,"STUDENT"); if(!"WITHDRAWN".equals(target))throw new SecurityException("Students may only withdraw their own applications through the student-specific endpoint."); try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM applications a JOIN students s ON s.id=a.student_id WHERE a.id=? AND s.user_id=? AND a.status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED')")){ps.setObject(1,aid);ps.setObject(2,actor);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new SecurityException("This application cannot be withdrawn by your account.");}} }
            if(!transitionAllowed(old,target))throw new IllegalArgumentException("Transition from " + old + " to " + target + " is not allowed.");
            try(PreparedStatement ps=c.prepareStatement("UPDATE applications SET status=?::application_status,school_note=CASE WHEN ? THEN ? ELSE school_note END,company_note=CASE WHEN ? THEN ? ELSE company_note END,updated_by=?,updated_at=NOW() WHERE id=?")){ps.setString(1,target);ps.setBoolean(2,admin);ps.setString(3,reason);ps.setBoolean(4,companyUser);ps.setString(5,reason);ps.setObject(6,actor);ps.setObject(7,aid);ps.executeUpdate();}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO application_status_history(application_id,old_status,new_status,changed_by,reason) VALUES (?,?::application_status,?::application_status,?,?)")){ps.setObject(1,aid);ps.setString(2,old);ps.setString(3,target);ps.setObject(4,actor);ps.setString(5,reason);ps.executeUpdate();}
            audit(c,actor,"APPLICATION_STATUS_CHANGED","application",aid,old+" -> "+target+"; "+reason);c.commit();
        }catch(Exception ex){c.rollback();throw ex;}finally{c.setAutoCommit(true);}}
    }
    private static boolean transitionAllowed(String old,String next){
        Map<String,Set<String>> flow=Map.of(
            "SUBMITTED",Set.of("UNDER_REVIEW","REJECTED","WITHDRAWN","CANCELLED"),
            "UNDER_REVIEW",Set.of("SHORTLISTED","FORWARDED","REJECTED","WITHDRAWN","CANCELLED"),
            "SHORTLISTED",Set.of("FORWARDED","REJECTED","WITHDRAWN","CANCELLED"),
            "FORWARDED",Set.of("COMPANY_REVIEW","REJECTED","WITHDRAWN","CANCELLED"),
            "COMPANY_REVIEW",Set.of("INTERVIEW","ACCEPTED","DECLINED","REJECTED","WITHDRAWN"),
            "INTERVIEW",Set.of("ACCEPTED","DECLINED","REJECTED","WITHDRAWN"));
        return flow.getOrDefault(old,Set.of()).contains(next);
    }

    public UUID createPlacementFromAcceptedApplication(UUID actor, String applicationId, LocalDate start, LocalDate end, String location, String title) throws Exception {
        UUID aid=uuid(applicationId);if(start==null||end==null||end.isBefore(start))throw new IllegalArgumentException("Valid placement start and end dates are required.");
        try(Connection c=connect()){c.setAutoCommit(false);try{requireActiveUser(c,actor);requireRole(c,actor,"SUPER_ADMIN","INTERNSHIP_COORDINATOR","PLACEMENT_OFFICER");UUID sid,cid,cycle,oid;String status;
            try(PreparedStatement ps=c.prepareStatement("SELECT student_id,company_id,cycle_id,opportunity_id,status::text FROM applications WHERE id=? FOR UPDATE")){ps.setObject(1,aid);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Application not found.");sid=rs.getObject(1,UUID.class);cid=rs.getObject(2,UUID.class);cycle=rs.getObject(3,UUID.class);oid=rs.getObject(4,UUID.class);status=rs.getString(5);}}
            if(!"ACCEPTED".equals(status))throw new IllegalArgumentException("Only an accepted application can become a placement.");
            try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM placements WHERE cycle_id=? AND student_id=? AND status IN ('OFFERED','PENDING_CONFIRMATION','CONFIRMED','ACTIVE','SUSPENDED')")){ps.setObject(1,cycle);ps.setObject(2,sid);try(ResultSet rs=ps.executeQuery()){if(rs.next())throw new IllegalArgumentException("This student already has an active placement in the cycle.");}}
            UUID pid;try(PreparedStatement ps=c.prepareStatement("INSERT INTO placements(cycle_id,student_id,company_id,opportunity_id,application_id,position_title,work_location,start_date,end_date,status,created_by) VALUES (?,?,?,?,?,?,?,?,?,'OFFERED',?) RETURNING id")){ps.setObject(1,cycle);ps.setObject(2,sid);ps.setObject(3,cid);ps.setObject(4,oid);ps.setObject(5,aid);ps.setString(6,title);ps.setString(7,location);ps.setObject(8,start);ps.setObject(9,end);ps.setObject(10,actor);try(ResultSet rs=ps.executeQuery()){rs.next();pid=rs.getObject(1,UUID.class);}}
            audit(c,actor,"PLACEMENT_CREATED","placement",pid,"Created from accepted application " + aid);c.commit();return pid;
        }catch(Exception ex){c.rollback();throw ex;}finally{c.setAutoCommit(true);}}
    }
}
