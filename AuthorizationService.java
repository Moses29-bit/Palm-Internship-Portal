/**
 * Central authorization helper for Phase 1.
 *
 * Usage:
 *   AuthorizationService.requirePermission(role, Permission.REVIEW_APPLICATIONS);
 */
public final class AuthorizationService {

    private AuthorizationService() {}

    public static boolean hasPermission(Role role, Permission permission) {
        return role != null
            && permission != null
            && RolePermissions.forRole(role).contains(permission);
    }

    public static void requirePermission(Role role, Permission permission) {
        if (!hasPermission(role, permission)) {
            throw new SecurityException("Access denied: " + permission);
        }
    }
}
