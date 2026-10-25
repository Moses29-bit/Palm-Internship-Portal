import java.time.Instant;
import java.time.LocalDate;

/**
 * Internship-cycle domain model.
 *
 * Persistence is intentionally not included yet.
 * Phase 2 will add PostgreSQL/Neon persistence.
 */
public class InternshipCycle {
    private long id;
    private String name;
    private LocalDate applicationOpen;
    private LocalDate applicationClose;
    private LocalDate internshipStart;
    private LocalDate internshipEnd;
    private CycleStatus status;
    private Instant createdAt;

    public InternshipCycle() {}

    public InternshipCycle(
            long id,
            String name,
            LocalDate applicationOpen,
            LocalDate applicationClose,
            LocalDate internshipStart,
            LocalDate internshipEnd,
            CycleStatus status,
            Instant createdAt) {
        this.id = id;
        this.name = name;
        this.applicationOpen = applicationOpen;
        this.applicationClose = applicationClose;
        this.internshipStart = internshipStart;
        this.internshipEnd = internshipEnd;
        this.status = status;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public LocalDate getApplicationOpen() { return applicationOpen; }
    public LocalDate getApplicationClose() { return applicationClose; }
    public LocalDate getInternshipStart() { return internshipStart; }
    public LocalDate getInternshipEnd() { return internshipEnd; }
    public CycleStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(CycleStatus status) { this.status = status; }
}
