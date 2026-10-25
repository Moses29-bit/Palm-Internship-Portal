import java.time.Instant;

/**
 * A controlled exception path for students who are not currently
 * on the administrator's eligibility list.
 */
public class AccessRequest {
    private long id;
    private String name;
    private String email;
    private String studentId;
    private String programme;
    private String level;
    private String reason;
    private AccessRequestStatus status;
    private Instant createdAt;
    private Instant reviewedAt;
    private String reviewedBy;

    public AccessRequest() {}

    public long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getStudentId() { return studentId; }
    public String getProgramme() { return programme; }
    public String getLevel() { return level; }
    public String getReason() { return reason; }
    public AccessRequestStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewedBy() { return reviewedBy; }
}
