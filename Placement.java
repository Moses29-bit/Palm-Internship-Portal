import java.time.Instant;
import java.time.LocalDate;

/**
 * Placement is the central internship relationship:
 * student + company + opportunity + cycle + supervisor.
 */
public class Placement {
    private long id;
    private long cycleId;
    private long studentId;
    private long companyId;
    private long opportunityId;
    private Long supervisorId;
    private LocalDate startDate;
    private LocalDate endDate;
    private PlacementStatus status;
    private Instant createdAt;
    private Instant confirmedAt;

    public Placement() {}

    public long getId() { return id; }
    public long getCycleId() { return cycleId; }
    public long getStudentId() { return studentId; }
    public long getCompanyId() { return companyId; }
    public long getOpportunityId() { return opportunityId; }
    public Long getSupervisorId() { return supervisorId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public PlacementStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
