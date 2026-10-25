import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Phases 5–7 data-access service for the Phase 2A PostgreSQL schema.
 * This class deliberately accepts authenticated user IDs from the caller;
 * authorization and ownership checks must be enforced in the HTTP/controller layer.
 */
public final class InternshipOperationsService {
    private InternshipOperationsService() {}

    private static Connection open() throws SQLException {
        return Database.getConnection();
    }
    private static UUID uuid(String value, String field) {
        try { return UUID.fromString(Objects.requireNonNull(value, field)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid " + field + " identifier."); }
    }
    private static String required(String value, String field, int max) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required.");
        String v = value.trim();
        if (v.length() > max) throw new IllegalArgumentException(field + " exceeds " + max + " characters.");
        return v;
    }
    private static void audit(Connection c, UUID actor, String action, String entityType, UUID entityId, String details) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO audit_logs(actor_user_id, action, entity_type, entity_id, details) VALUES (?, ?, ?, ?, CAST(? AS jsonb))")) {
            p.setObject(1, actor); p.setString(2, action); p.setString(3, entityType); p.setObject(4, entityId);
            p.setString(5, details == null ? "{}" : details); p.executeUpdate();
        }
    }
    private static void notifyUser(Connection c, UUID userId, String type, String title, String message, String entity, UUID relatedId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO notifications(user_id, notification_type, title, message, related_entity, related_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            p.setObject(1, userId); p.setString(2, type); p.setString(3, required(title,"title",250));
            p.setString(4, required(message,"message",4000)); p.setString(5, entity); p.setObject(6, relatedId); p.executeUpdate();
        }
    }

    /** Create or update one attendance row per placement/day. */
    public static UUID recordAttendance(String actorId, String placementId, String date, String checkIn, String checkOut, String notes) throws SQLException {
        UUID actor=uuid(actorId,"actor"), placement=uuid(placementId,"placement"); LocalDate day=LocalDate.parse(required(date,"date",10));
        if (checkIn != null && !checkIn.isBlank() && checkOut != null && !checkOut.isBlank() && Timestamp.valueOf(checkOut.replace('T',' ')).before(Timestamp.valueOf(checkIn.replace('T',' ')))) throw new IllegalArgumentException("Check-out cannot be earlier than check-in.");
        try (Connection c=open()) { c.setAutoCommit(false); try {
            UUID id;
            try (PreparedStatement p=c.prepareStatement("INSERT INTO attendance(placement_id, attendance_date, check_in, check_out, status, notes) VALUES (?, ?, ?::timestamptz, ?::timestamptz, 'PENDING_REVIEW', ?) ON CONFLICT (placement_id, attendance_date) DO UPDATE SET check_in=EXCLUDED.check_in, check_out=EXCLUDED.check_out, notes=EXCLUDED.notes, status='PENDING_REVIEW', updated_at=NOW() RETURNING id")) {
                p.setObject(1,placement); p.setObject(2,day); p.setString(3,blankToNull(checkIn)); p.setString(4,blankToNull(checkOut)); p.setString(5, notes==null?null:notes.trim());
                try(ResultSet r=p.executeQuery()){r.next();id=r.getObject(1,UUID.class);}
            }
            audit(c,actor,"ATTENDANCE_RECORDED","attendance",id,"{}"); c.commit(); return id;
        } catch(Exception e){c.rollback();throw e;} }
    }

    public static void reviewAttendance(String actorId, String attendanceId, String status, String notes) throws SQLException {
        UUID actor=uuid(actorId,"actor"), id=uuid(attendanceId,"attendance"); String s=required(status,"status",30).toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED","ABSENT","LATE","EXCUSED","DISPUTED").contains(s)) throw new IllegalArgumentException("Unsupported attendance review status.");
        try(Connection c=open()){c.setAutoCommit(false);try{
            try(PreparedStatement p=c.prepareStatement("UPDATE attendance SET status=CAST(? AS attendance_status), notes=COALESCE(?,notes), reviewed_by=?, reviewed_at=NOW(), updated_at=NOW() WHERE id=?")){
                p.setString(1,s);p.setString(2,notes);p.setObject(3,actor);p.setObject(4,id);if(p.executeUpdate()!=1)throw new IllegalArgumentException("Attendance record not found.");}
            audit(c,actor,"ATTENDANCE_REVIEWED","attendance",id,"{\"status\":\""+s+"\"}");c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }

    public static UUID saveLogbook(String actorId, String placementId, String studentId, int week, String activities, String outcomes, String challenges, String nextSteps, boolean submit) throws SQLException {
        UUID actor=uuid(actorId,"actor"), placement=uuid(placementId,"placement"), student=uuid(studentId,"student");
        if(week<1||week>104)throw new IllegalArgumentException("Week number must be between 1 and 104.");
        String act=required(activities,"activities",12000); String status=submit?"SUBMITTED":"DRAFT";
        try(Connection c=open()){c.setAutoCommit(false);try{
            UUID id;
            try(PreparedStatement p=c.prepareStatement("INSERT INTO logbook_entries(placement_id,student_id,week_number,activities,learning_outcomes,challenges,next_steps,status,submitted_at) VALUES(?,?,?,?,?,?,?,CAST(? AS logbook_status),CASE WHEN ? THEN NOW() ELSE NULL END) ON CONFLICT(placement_id,week_number) DO UPDATE SET activities=EXCLUDED.activities,learning_outcomes=EXCLUDED.learning_outcomes,challenges=EXCLUDED.challenges,next_steps=EXCLUDED.next_steps,status=EXCLUDED.status,submitted_at=EXCLUDED.submitted_at,updated_at=NOW() WHERE logbook_entries.student_id=EXCLUDED.student_id AND logbook_entries.status IN ('DRAFT','RETURNED') RETURNING id")){
                p.setObject(1,placement);p.setObject(2,student);p.setInt(3,week);p.setString(4,act);p.setString(5,outcomes);p.setString(6,challenges);p.setString(7,nextSteps);p.setString(8,status);p.setBoolean(9,submit);
                try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalStateException("Logbook could not be saved. It may already be approved or submitted by another user.");id=r.getObject(1,UUID.class);}}
            audit(c,actor,submit?"LOGBOOK_SUBMITTED":"LOGBOOK_SAVED","logbook_entries",id,"{\"week\":"+week+"}");c.commit();return id;
        }catch(Exception e){c.rollback();throw e;}}
    }

    public static void reviewLogbook(String actorId,String entryId,boolean approve,String comment) throws SQLException {
        UUID actor=uuid(actorId,"actor"), id=uuid(entryId,"logbook entry"); String status=approve?"APPROVED":"RETURNED";
        try(Connection c=open()){c.setAutoCommit(false);try{
            try(PreparedStatement p=c.prepareStatement("UPDATE logbook_entries SET status=CAST(? AS logbook_status),reviewed_by=?,reviewed_at=NOW(),review_comment=?,updated_at=NOW() WHERE id=? AND status='SUBMITTED'")){
                p.setString(1,status);p.setObject(2,actor);p.setString(3,comment);p.setObject(4,id);if(p.executeUpdate()!=1)throw new IllegalArgumentException("Submitted logbook entry not found.");}
            audit(c,actor,"LOGBOOK_"+status,"logbook_entries",id,"{}");c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }

    public static UUID recordMonitoringVisit(String actorId,String placementId,String visitDate,String visitType,String location,String observations,String actions,String outcome) throws SQLException {
        UUID actor=uuid(actorId,"actor"), placement=uuid(placementId,"placement");
        try(Connection c=open()){c.setAutoCommit(false);try{
            UUID id;try(PreparedStatement p=c.prepareStatement("INSERT INTO monitoring_visits(placement_id,coordinator_id,visit_date,visit_type,location,observations,action_items,outcome) VALUES(?,?,?,?,?,?,?,?) RETURNING id")){
                p.setObject(1,placement);p.setObject(2,actor);p.setObject(3,LocalDate.parse(required(visitDate,"visit date",10)));p.setString(4,visitType==null?"ROUTINE":required(visitType,"visit type",50));p.setString(5,location);p.setString(6,required(observations,"observations",12000));p.setString(7,actions);p.setString(8,outcome);try(ResultSet r=p.executeQuery()){r.next();id=r.getObject(1,UUID.class);}}
            audit(c,actor,"MONITORING_VISIT_RECORDED","monitoring_visits",id,"{}");c.commit();return id;
        }catch(Exception e){c.rollback();throw e;}}
    }

    public static UUID submitEvaluation(String actorId,String placementId,String type,String evaluatorName,Double score,Double rating,String strengths,String weaknesses,String comments) throws SQLException {
        UUID actor=uuid(actorId,"actor"), placement=uuid(placementId,"placement");
        if(score!=null&&(score<0||score>100))throw new IllegalArgumentException("Score must be between 0 and 100.");
        if(rating!=null&&(rating<0||rating>5))throw new IllegalArgumentException("Rating must be between 0 and 5.");
        try(Connection c=open()){c.setAutoCommit(false);try{
            UUID id;try(PreparedStatement p=c.prepareStatement("INSERT INTO evaluations(placement_id,evaluator_user_id,evaluator_name,evaluation_type,score,rating,strengths,weaknesses,comments,submitted_at) VALUES(?,?,?,?,?,?,?,?,?,NOW()) RETURNING id")){
                p.setObject(1,placement);p.setObject(2,actor);p.setString(3,evaluatorName);p.setString(4,required(type,"evaluation type",80));if(score==null)p.setNull(5,Types.NUMERIC);else p.setDouble(5,score);if(rating==null)p.setNull(6,Types.NUMERIC);else p.setDouble(6,rating);p.setString(7,strengths);p.setString(8,weaknesses);p.setString(9,comments);try(ResultSet r=p.executeQuery()){r.next();id=r.getObject(1,UUID.class);}}
            audit(c,actor,"EVALUATION_SUBMITTED","evaluations",id,"{}");c.commit();return id;
        }catch(Exception e){c.rollback();throw e;}}
    }

    public static UUID submitReport(String actorId,String placementId,String studentId,String type,String title,String dueDate) throws SQLException {
        UUID actor=uuid(actorId,"actor"), placement=uuid(placementId,"placement"), student=studentId==null||studentId.isBlank()?null:uuid(studentId,"student");
        String t=required(type,"report type",40).toUpperCase(Locale.ROOT);
        if(!Set.of("STUDENT_FINAL_REPORT","COMPANY_REPORT","UNIVERSITY_ASSESSMENT").contains(t))throw new IllegalArgumentException("Unsupported report type.");
        try(Connection c=open()){c.setAutoCommit(false);try{
            UUID id;try(PreparedStatement p=c.prepareStatement("INSERT INTO reports(placement_id,student_id,type,title,status,due_date,submitted_at) VALUES(?,?,CAST(? AS report_type),?,'SUBMITTED',?,NOW()) RETURNING id")){
                p.setObject(1,placement);p.setObject(2,student);p.setString(3,t);p.setString(4,required(title,"title",250));p.setObject(5,dueDate==null||dueDate.isBlank()?null:LocalDate.parse(dueDate));try(ResultSet r=p.executeQuery()){r.next();id=r.getObject(1,UUID.class);}}
            audit(c,actor,"REPORT_SUBMITTED","reports",id,"{\"type\":\""+t+"\"}");c.commit();return id;
        }catch(Exception e){c.rollback();throw e;}}
    }

    public static void reviewReport(String actorId,String reportId,String status,String comment) throws SQLException {
        UUID actor=uuid(actorId,"actor"), id=uuid(reportId,"report"); String s=required(status,"status",30).toUpperCase(Locale.ROOT);
        if(!Set.of("UNDER_REVIEW","APPROVED","RETURNED","REJECTED").contains(s))throw new IllegalArgumentException("Unsupported report review status.");
        try(Connection c=open()){c.setAutoCommit(false);try{
            try(PreparedStatement p=c.prepareStatement("UPDATE reports SET status=CAST(? AS report_status),reviewed_by=?,reviewed_at=NOW(),review_comment=?,updated_at=NOW() WHERE id=? AND status IN ('SUBMITTED','UNDER_REVIEW','RETURNED')")){
                p.setString(1,s);p.setObject(2,actor);p.setString(3,comment);p.setObject(4,id);if(p.executeUpdate()!=1)throw new IllegalArgumentException("Report cannot be reviewed in its current state.");}
            audit(c,actor,"REPORT_"+s,"reports",id,"{}");c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }

    /** Completion requires the placement to be active and a final report to be approved. */
    public static void completePlacement(String actorId,String placementId) throws SQLException {
        UUID actor=uuid(actorId,"actor"), placement=uuid(placementId,"placement");
        try(Connection c=open()){c.setAutoCommit(false);try{
            try(PreparedStatement check=c.prepareStatement("SELECT 1 FROM reports WHERE placement_id=? AND type='STUDENT_FINAL_REPORT' AND status='APPROVED' LIMIT 1")){check.setObject(1,placement);try(ResultSet r=check.executeQuery()){if(!r.next())throw new IllegalStateException("An approved student final report is required before completion.");}}
            try(PreparedStatement p=c.prepareStatement("UPDATE placements SET status='COMPLETED',completed_at=NOW(),updated_at=NOW() WHERE id=? AND status IN ('ACTIVE','CONFIRMED')")){p.setObject(1,placement);if(p.executeUpdate()!=1)throw new IllegalStateException("Only confirmed or active placements can be completed.");}
            audit(c,actor,"PLACEMENT_COMPLETED","placements",placement,"{}");c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }

    public static void createNotification(String actorId,String recipientUserId,String type,String title,String message,String relatedEntity,String relatedId) throws SQLException {
        UUID actor=uuid(actorId,"actor"), recipient=uuid(recipientUserId,"recipient"), related=relatedId==null||relatedId.isBlank()?null:uuid(relatedId,"related entity");
        try(Connection c=open()){c.setAutoCommit(false);try{notifyUser(c,recipient,required(type,"type",100),title,message,relatedEntity,related);audit(c,actor,"NOTIFICATION_CREATED","notifications",null,"{}");c.commit();}catch(Exception e){c.rollback();throw e;}}
    }

    public static void markNotificationRead(String userId,String notificationId) throws SQLException {
        UUID user=uuid(userId,"user"), id=uuid(notificationId,"notification");
        try(Connection c=open();PreparedStatement p=c.prepareStatement("UPDATE notifications SET read_at=COALESCE(read_at,NOW()) WHERE id=? AND user_id=?")){p.setObject(1,id);p.setObject(2,user);if(p.executeUpdate()!=1)throw new IllegalArgumentException("Notification not found.");}
    }

    /** Compact dashboard counts; use a dedicated report/export endpoint for detailed records. */
    public static Map<String,Long> dashboardCounts() throws SQLException {
        Map<String,Long> out=new LinkedHashMap<>();
        String[][] queries={
            {"active_students","SELECT COUNT(*) FROM students WHERE active=TRUE"},
            {"verified_companies","SELECT COUNT(*) FROM companies WHERE status IN ('VERIFIED','ACTIVE')"},
            {"open_opportunities","SELECT COUNT(*) FROM opportunities WHERE status='OPEN'"},
            {"submitted_applications","SELECT COUNT(*) FROM applications WHERE status IN ('SUBMITTED','UNDER_REVIEW','SHORTLISTED','FORWARDED','COMPANY_REVIEW','INTERVIEW')"},
            {"active_placements","SELECT COUNT(*) FROM placements WHERE status='ACTIVE'"},
            {"pending_attendance_reviews","SELECT COUNT(*) FROM attendance WHERE status='PENDING_REVIEW'"},
            {"pending_logbooks","SELECT COUNT(*) FROM logbook_entries WHERE status='SUBMITTED'"},
            {"pending_reports","SELECT COUNT(*) FROM reports WHERE status IN ('SUBMITTED','UNDER_REVIEW')"}
        };
        try(Connection c=open()){for(String[] q:queries){try(Statement s=c.createStatement();ResultSet r=s.executeQuery(q[1])){r.next();out.put(q[0],r.getLong(1));}}}
        return Collections.unmodifiableMap(out);
    }

    private static String blankToNull(String s){return s==null||s.isBlank()?null:s.trim();}
}
