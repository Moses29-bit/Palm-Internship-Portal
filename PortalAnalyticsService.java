import java.sql.*;
import java.util.*;

/** Aggregated, non-PII analytics queries for authorized administrators/coordinators. */
public final class PortalAnalyticsService {
    private PortalAnalyticsService() {}
    private static Connection open() throws SQLException {
        return Database.getConnection();
    }
    public static Map<String,Long> dashboardCounts() throws SQLException {
        Map<String,Long> values=new LinkedHashMap<>();
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT metric, metric_value FROM v_portal_dashboard_counts ORDER BY metric");ResultSet r=p.executeQuery()){
            while(r.next())values.put(r.getString(1),r.getLong(2));
        }
        return Collections.unmodifiableMap(values);
    }
    public static List<Map<String,Object>> placementsByStatus() throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>();
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT status::text, COUNT(*)::bigint FROM placements GROUP BY status ORDER BY COUNT(*) DESC");ResultSet r=p.executeQuery()){
            while(r.next()){Map<String,Object> row=new LinkedHashMap<>();row.put("status",r.getString(1));row.put("count",r.getLong(2));out.add(Collections.unmodifiableMap(row));}
        }
        return Collections.unmodifiableList(out);
    }
    public static List<Map<String,Object>> applicationsByStatus() throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>();
        try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT status::text, COUNT(*)::bigint FROM applications GROUP BY status ORDER BY COUNT(*) DESC");ResultSet r=p.executeQuery()){
            while(r.next()){Map<String,Object> row=new LinkedHashMap<>();row.put("status",r.getString(1));row.put("count",r.getLong(2));out.add(Collections.unmodifiableMap(row));}
        }
        return Collections.unmodifiableList(out);
    }
}
