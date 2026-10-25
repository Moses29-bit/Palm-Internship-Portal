import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * Centralized configuration for the Phase 2B PostgreSQL connectivity check.
 * Uses the portal's existing environment loader, including .env and .env.local.
 */
public final class DatabaseConfig {

    private DatabaseConfig() {}

    public static String jdbcUrl() {
        String raw = firstNonBlank(env("NEON_DATABASE_URL"), env("DATABASE_URL"));
        if (raw == null) {
            String host = env("PGHOST");
            String port = firstNonBlank(env("PGPORT"), "5432");
            String database = env("PGDATABASE");
            require(host, "PGHOST");
            require(database, "PGDATABASE");
            require(env("PGUSER"), "PGUSER");
            require(env("PGPASSWORD"), "PGPASSWORD");
            return ensureSslMode("jdbc:postgresql://" + host + ":" + port + "/" + database);
        }

        URI uri = toUri(raw);
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank() || uri.getRawPath() == null
                || uri.getRawPath().length() < 2 || uri.getRawPath().equals("/")) {
            throw new IllegalArgumentException("PostgreSQL URL must include a host and database name.");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("PostgreSQL URL must not contain a fragment.");
        }

        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        String url = "jdbc:postgresql://" + authority + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return ensureSslMode(url);
    }

    public static Properties connectionProperties() {
        Properties properties = new Properties();
        String raw = firstNonBlank(env("NEON_DATABASE_URL"), env("DATABASE_URL"));
        if (raw != null) {
            String userInfo = toUri(raw).getRawUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                if (separator < 1 || separator == userInfo.length() - 1) {
                    throw new IllegalArgumentException("PostgreSQL URL must include both username and password.");
                }
                properties.setProperty("user", decode(userInfo.substring(0, separator)));
                properties.setProperty("password", decode(userInfo.substring(separator + 1)));
            }
        }

        String user = firstNonBlank(env("PGUSER"), env("DB_USER"));
        String password = firstNonBlank(env("PGPASSWORD"), env("DB_PASS"));
        if (!properties.containsKey("user") && user != null) properties.setProperty("user", user);
        if (!properties.containsKey("password") && password != null) properties.setProperty("password", password);
        if (!properties.containsKey("user") || !properties.containsKey("password")) {
            throw new IllegalStateException(
                "PostgreSQL credentials are missing. Set credentials in NEON_DATABASE_URL or configure PGUSER and PGPASSWORD."
            );
        }
        return properties;
    }

    private static String env(String name) {
        return Server.env(name);
    }

    private static URI toUri(String raw) {
        String value = raw.trim();
        if (value.regionMatches(true, 0, "jdbc:", 0, 5)) value = value.substring(5);
        if (!value.regionMatches(true, 0, "postgresql://", 0, 13)
                && !value.regionMatches(true, 0, "postgres://", 0, 10)) {
            throw new IllegalArgumentException(
                "Unsupported database URL. Expected postgresql://... or jdbc:postgresql://..."
            );
        }
        return URI.create(value);
    }

    private static String ensureSslMode(String jdbcUrl) {
        if (jdbcUrl.toLowerCase(Locale.ROOT).matches("(?s).*([?&])sslmode=[^&]*.*")) return jdbcUrl;
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "sslmode=require";
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured.");
        }
    }
}
