/**
 * Valid application-state transitions.
 *
 * This prevents arbitrary state changes in the new workflow.
 */
public final class ApplicationWorkflow {

    private ApplicationWorkflow() {}

    public static boolean canTransition(
            ApplicationStatus from,
            ApplicationStatus to) {

        if (from == null || to == null || from == to) {
            return false;
        }

        return switch (from) {
            case DRAFT ->
                to == ApplicationStatus.SUBMITTED;

            case SUBMITTED ->
                to == ApplicationStatus.UNDER_REVIEW
                || to == ApplicationStatus.WITHDRAWN;

            case UNDER_REVIEW ->
                to == ApplicationStatus.SHORTLISTED
                || to == ApplicationStatus.REJECTED;

            case SHORTLISTED ->
                to == ApplicationStatus.FORWARDED
                || to == ApplicationStatus.REJECTED;

            case FORWARDED ->
                to == ApplicationStatus.COMPANY_REVIEW;

            case COMPANY_REVIEW ->
                to == ApplicationStatus.INTERVIEW
                || to == ApplicationStatus.ACCEPTED
                || to == ApplicationStatus.REJECTED;

            case INTERVIEW ->
                to == ApplicationStatus.ACCEPTED
                || to == ApplicationStatus.REJECTED;

            case ACCEPTED, REJECTED, WITHDRAWN, DECLINED,
                 EXPIRED, CANCELLED ->
                false;
        };
    }
}
