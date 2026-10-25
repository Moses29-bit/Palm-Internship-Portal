import java.sql.SQLException;

/**
 * Read-only connectivity check for PostgreSQL/Neon.
 */
public final class DatabaseHealthCheck {

    private DatabaseHealthCheck() {}

    public static void main(String[] args) {
        System.out.println("Palm Internship Portal - Neon Health Check");
        try {
            Database.DatabaseStatus status = Database.testConnection();
            System.out.println("STATUS: CONNECTED");
            System.out.println("Database: " + status.databaseName());
            System.out.println("Server: " + shortenVersion(status.serverVersion()));
            System.out.println("Java -> PostgreSQL -> Neon connection is working.");
        } catch (Exception ex) {
            System.err.println("STATUS: FAILED");
            System.err.println("Reason: " + safeReason(ex));
            System.exit(1);
        }
    }

    private static String safeReason(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return sql.getClass().getSimpleName() + (sql.getSQLState() == null ? "" : " (SQLState " + sql.getSQLState() + ")");
            }
        }
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            return failure.getMessage();
        }
        return failure.getClass().getSimpleName();
    }

    private static String shortenVersion(String version) {
        if (version == null) return "Unknown";
        String cleaned = version.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 177) + "..." : cleaned;
    }
}
