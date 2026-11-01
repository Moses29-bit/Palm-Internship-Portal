import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

public final class FakeDb implements Driver {
    private static final Map<String, List<Object[]>> TABLES = new HashMap<>();
    private static final Pattern SELECT = Pattern.compile("SELECT (.+) FROM ([`\"])([A-Za-z0-9_]+)\\2 ORDER BY (.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE = Pattern.compile("DELETE FROM ([`\"])([A-Za-z0-9_]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT = Pattern.compile("INSERT INTO ([`\"])([A-Za-z0-9_]+)\\1 \\((.+)\\) VALUES \\((.+)\\)", Pattern.CASE_INSENSITIVE);

    static {
        try { DriverManager.registerDriver(new FakeDb()); }
        catch (SQLException e) { throw new ExceptionInInitializerError(e); }
    }

    public static void main(String[] args) throws Exception { Server.main(args); }

    @Override public boolean acceptsURL(String url) {
        return url != null && (url.startsWith("jdbc:mariadb://fake/") || url.startsWith("jdbc:postgresql://fake/"));
    }

    @Override public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        boolean postgres = url.startsWith("jdbc:postgresql:");
        char quote = postgres ? '"' : '`';
        InvocationHandler connection = (proxy, method, args) -> switch (method.getName()) {
            case "createStatement" -> statement(quote);
            case "prepareStatement" -> prepared(quote, (String) args[0]);
            case "setAutoCommit", "commit", "rollback", "close" -> null;
            case "getAutoCommit" -> false;
            case "isClosed" -> false;
            case "unwrap" -> throw new SQLException("Not a wrapper");
            case "isWrapperFor" -> false;
            default -> defaultValue(method.getReturnType());
        };
        return proxy(Connection.class, connection);
    }

    private static Statement statement(char quote) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> select((String) args[0], quote);
            case "executeUpdate" -> delete((String) args[0], quote);
            case "execute" -> { String sql = (String) args[0]; if (sql.trim().startsWith("DELETE")) delete(sql, quote); yield false; }
            case "close" -> null;
            case "getConnection" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return proxy(Statement.class, handler);
    }

    private static PreparedStatement prepared(char quote, String sql) throws SQLException {
        Matcher match = INSERT.matcher(sql);
        if (!match.matches() || match.group(1).charAt(0) != quote) throw new SQLException("Wrong dialect or invalid INSERT: " + sql);
        String table = match.group(2);
        String[] columns = identifiers(match.group(3), quote);
        if (!match.group(4).equals("?,".repeat(columns.length - 1) + "?")) throw new SQLException("Invalid INSERT placeholders: " + sql);
        List<Object[]> batch = new ArrayList<>();
        Map<Integer, Object> parameters = new HashMap<>();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "setObject" -> { parameters.put((Integer) args[0], args[1]); yield null; }
            case "setNull" -> { parameters.put((Integer) args[0], null); yield null; }
            case "addBatch" -> { Object[] row = new Object[columns.length]; for (int i = 0; i < row.length; i++) row[i] = parameters.get(i + 1); batch.add(row); yield null; }
            case "executeBatch" -> { synchronized (TABLES) { TABLES.computeIfAbsent(table, key -> new ArrayList<>()).addAll(batch); } yield new int[batch.size()]; }
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        };
        return proxy(PreparedStatement.class, handler);
    }

    private static ResultSet select(String sql, char quote) throws SQLException {
        Matcher match = SELECT.matcher(sql);
        if (!match.matches() || match.group(2).charAt(0) != quote) throw new SQLException("Wrong dialect or invalid SELECT: " + sql);
        String table = match.group(3);
        String[] columns = identifiers(match.group(1), quote);
        identifiers(match.group(4), quote);
        List<Object[]> rows;
        synchronized (TABLES) { rows = new ArrayList<>(TABLES.getOrDefault(table, List.of())); }
        int[] types = Arrays.stream(columns).mapToInt(column -> column.equals("salt") || column.equals("pass_hash") ? Types.VARBINARY : Types.VARCHAR).toArray();
        ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (p, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> columns.length;
            case "getColumnType" -> types[(Integer) args[0] - 1];
            case "getColumnLabel", "getColumnName" -> columns[(Integer) args[0] - 1];
            default -> defaultValue(method.getReturnType());
        });
        int[] cursor = {-1};
        InvocationHandler handler = (p, method, args) -> {
            int index = method.getName().startsWith("get") && args != null && args.length > 0 && args[0] instanceof Integer n ? n - 1 : -1;
            return switch (method.getName()) {
                case "next" -> ++cursor[0] < rows.size();
                case "getMetaData" -> metadata;
                case "getString" -> value(rows, cursor[0], index) == null ? null : value(rows, cursor[0], index).toString();
                case "getBytes" -> (byte[]) value(rows, cursor[0], index);
                case "close" -> null;
                case "wasNull" -> false;
                default -> defaultValue(method.getReturnType());
            };
        };
        return proxy(ResultSet.class, handler);
    }

    private static int delete(String sql, char quote) throws SQLException {
        Matcher match = DELETE.matcher(sql);
        if (!match.matches() || match.group(1).charAt(0) != quote) throw new SQLException("Wrong dialect or invalid DELETE: " + sql);
        synchronized (TABLES) {
            List<Object[]> rows = TABLES.computeIfAbsent(match.group(2), key -> new ArrayList<>());
            int count = rows.size();
            rows.clear();
            return count;
        }
    }

    private static Object value(List<Object[]> rows, int row, int column) {
        return row < 0 || row >= rows.size() || column < 0 ? null : rows.get(row)[column];
    }

    private static String[] identifiers(String source, char quote) throws SQLException {
        String[] parts = source.split(",");
        for (String part : parts) {
            String value = part.trim();
            if (value.length() < 3 || value.charAt(0) != quote || value.charAt(value.length() - 1) != quote)
                throw new SQLException("Identifier does not use expected " + quote + " quoting: " + value);
        }
        return Arrays.stream(parts).map(part -> part.trim().substring(1, part.trim().length() - 1)).toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(FakeDb.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }

    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
}
