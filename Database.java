import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Minimal PostgreSQL access layer used to verify Neon connectivity.
 * The existing Server persistence remains unchanged in this phase.
 */
public final class Database {

    @FunctionalInterface
    public interface ConnectionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private Database() {}

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(
                "PostgreSQL JDBC driver was not found. Add the PostgreSQL JDBC dependency.",
                ex
            );
        }
    }

    public static Connection getConnection() throws SQLException {
        var properties = DatabaseConfig.connectionProperties();
        properties.setProperty("currentSchema", "palm_portal_v2");
        return DriverManager.getConnection(DatabaseConfig.jdbcUrl(), properties);
    }

    public static <T> T withConnection(ConnectionWork<T> work) throws SQLException {
        try (Connection connection = getConnection()) {
            return work.execute(connection);
        }
    }

    public static <T> T transaction(ConnectionWork<T> work) throws SQLException {
        try (Connection connection = getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
                throw ex;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public static DatabaseStatus testConnection() throws SQLException {
        return withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT current_database(), version()"
                 )) {
                if (!result.next()) throw new SQLException("PostgreSQL returned no database information.");
                return new DatabaseStatus(result.getString(1), result.getString(2));
            }
        });
    }

    public record DatabaseStatus(String databaseName, String serverVersion) {}
}
