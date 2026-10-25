import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Maps each role to its permissions.
 *
 * This class is deliberately independent of the database.
 * Phase 2 will persist users/roles in PostgreSQL.
 */
public final class RolePermissions {

    private RolePermissions() {}

    public static Set<Permission> forRole(Role role) {
        if (role == null) {
            return Collections.emptySet();
        }

        return switch (role) {
            case SUPER_ADMIN ->
                EnumSet.allOf(Permission.class);

            case INTERNSHIP_COORDINATOR ->
                EnumSet.of(
                    Permission.VIEW_STUDENTS,
                    Permission.MANAGE_STUDENTS,
                    Permission.VIEW_STUDENT_LIST,
                    Permission.MANAGE_STUDENT_LIST,
                    Permission.REVIEW_ACCESS_REQUESTS,
                    Permission.VIEW_COMPANIES,
                    Permission.MANAGE_COMPANIES,
                    Permission.VERIFY_COMPANIES,
                    Permission.VIEW_OPPORTUNITIES,
                    Permission.MANAGE_OPPORTUNITIES,
                    Permission.VIEW_APPLICATIONS,
                    Permission.REVIEW_APPLICATIONS,
                    Permission.VIEW_PLACEMENTS,
                    Permission.CREATE_PLACEMENT,
                    Permission.MANAGE_PLACEMENTS,
                    Permission.VIEW_ATTENDANCE,
                    Permission.REVIEW_ATTENDANCE,
                    Permission.VIEW_LOGBOOKS,
                    Permission.REVIEW_LOGBOOKS,
                    Permission.VIEW_REPORTS,
                    Permission.REVIEW_REPORTS,
                    Permission.VIEW_EVALUATIONS,
                    Permission.MANAGE_EVALUATIONS,
                    Permission.VIEW_INTERNSHIP_CYCLES,
                    Permission.MANAGE_INTERNSHIP_CYCLES,
                    Permission.SEND_NOTIFICATIONS,
                    Permission.VIEW_ANALYTICS
                );

            case PLACEMENT_OFFICER ->
                EnumSet.of(
                    Permission.VIEW_STUDENTS,
                    Permission.VIEW_STUDENT_LIST,
                    Permission.VIEW_COMPANIES,
                    Permission.MANAGE_COMPANIES,
                    Permission.VIEW_OPPORTUNITIES,
                    Permission.MANAGE_OPPORTUNITIES,
                    Permission.VIEW_APPLICATIONS,
                    Permission.REVIEW_APPLICATIONS,
                    Permission.VIEW_PLACEMENTS,
                    Permission.CREATE_PLACEMENT,
                    Permission.MANAGE_PLACEMENTS,
                    Permission.SEND_NOTIFICATIONS
                );

            case DEPARTMENT_COORDINATOR ->
                EnumSet.of(
                    Permission.VIEW_STUDENTS,
                    Permission.VIEW_APPLICATIONS,
                    Permission.VIEW_PLACEMENTS,
                    Permission.VIEW_ATTENDANCE,
                    Permission.VIEW_LOGBOOKS,
                    Permission.REVIEW_LOGBOOKS,
                    Permission.VIEW_REPORTS,
                    Permission.REVIEW_REPORTS,
                    Permission.VIEW_EVALUATIONS,
                    Permission.VIEW_ANALYTICS
                );

            case AUDITOR ->
                EnumSet.of(
                    Permission.VIEW_STUDENTS,
                    Permission.VIEW_COMPANIES,
                    Permission.VIEW_APPLICATIONS,
                    Permission.VIEW_PLACEMENTS,
                    Permission.VIEW_ATTENDANCE,
                    Permission.VIEW_LOGBOOKS,
                    Permission.VIEW_REPORTS,
                    Permission.VIEW_EVALUATIONS,
                    Permission.VIEW_ANALYTICS,
                    Permission.VIEW_AUDIT_LOG
                );

            case STUDENT ->
                EnumSet.of(
                    Permission.VIEW_OPPORTUNITIES,
                    Permission.VIEW_APPLICATIONS,
                    Permission.VIEW_PLACEMENTS,
                    Permission.VIEW_ATTENDANCE,
                    Permission.VIEW_LOGBOOKS,
                    Permission.VIEW_REPORTS,
                    Permission.VIEW_EVALUATIONS
                );

            case COMPANY_ADMIN ->
                EnumSet.of(
                    Permission.VIEW_COMPANIES,
                    Permission.MANAGE_COMPANIES,
                    Permission.VIEW_OPPORTUNITIES,
                    Permission.MANAGE_OPPORTUNITIES,
                    Permission.VIEW_APPLICATIONS,
                    Permission.REVIEW_APPLICATIONS,
                    Permission.VIEW_PLACEMENTS,
                    Permission.MANAGE_PLACEMENTS,
                    Permission.VIEW_ATTENDANCE,
                    Permission.REVIEW_ATTENDANCE,
                    Permission.VIEW_LOGBOOKS,
                    Permission.REVIEW_LOGBOOKS,
                    Permission.VIEW_EVALUATIONS,
                    Permission.MANAGE_EVALUATIONS
                );

            case COMPANY_SUPERVISOR ->
                EnumSet.of(
                    Permission.VIEW_PLACEMENTS,
                    Permission.VIEW_ATTENDANCE,
                    Permission.REVIEW_ATTENDANCE,
                    Permission.VIEW_LOGBOOKS,
                    Permission.REVIEW_LOGBOOKS,
                    Permission.VIEW_EVALUATIONS,
                    Permission.MANAGE_EVALUATIONS
                );
        };
    }
}
