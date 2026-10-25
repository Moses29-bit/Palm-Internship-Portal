import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only example repository for the isolated Phase 2 schema.
 * It does not replace or alter the current Server.java persistence layer.
 */
public final class StudentRepository {

    public record StudentSummary(
        UUID id,
        String fullName,
        String institutionalEmail,
        String programme,
        boolean active
    ) {}

    public Optional<StudentSummary> findById(UUID studentId) throws SQLException {
        String sql = """
            SELECT s.id, s.full_name, s.institutional_email, p.name AS programme_name, s.active
            FROM palm_portal_v2.students s
            LEFT JOIN palm_portal_v2.programmes p ON p.id = s.programme_id
            WHERE s.id = ?
            """;

        return Database.withConnection(connection -> findById(connection, sql, studentId));
    }

    private Optional<StudentSummary> findById(
            Connection connection, String sql, UUID studentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, studentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new StudentSummary(
                    result.getObject("id", UUID.class),
                    result.getString("full_name"),
                    result.getString("institutional_email"),
                    result.getString("programme_name"),
                    result.getBoolean("active")
                ));
            }
        }
    }
}
