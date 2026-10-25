import java.time.Instant;

/**
 * Represents a student who is eligible for an internship cycle.
 */
public class StudentEligibility {
    private long id;
    private String studentId;
    private String name;
    private String email;
    private String programme;
    private String level;
    private boolean eligible;
    private RegistrationStatus registrationStatus;
    private long cycleId;
    private Instant importedAt;

    public StudentEligibility() {}

    public long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getProgramme() { return programme; }
    public String getLevel() { return level; }
    public boolean isEligible() { return eligible; }
    public RegistrationStatus getRegistrationStatus() { return registrationStatus; }
    public long getCycleId() { return cycleId; }
    public Instant getImportedAt() { return importedAt; }
}
