package db.migration;

import com.delivery.harness.common.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Portable one-time migration; customer JSON is never interpreted by a database-specific JSON
 * dialect. Legacy payloads may carry inline attachment bodies, so every scan streams one row at a
 * time (Flyway's migration transaction lets PostgreSQL honour the cursor fetch size).
 */
public class V4__backfill_metadata_projections extends BaseJavaMigration {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        backfill(connection, true);
        backfill(connection, false);
        backfillAudits(connection);
    }

    /**
     * Record writes audit their kind, so scope comes from the exact (tenant, kind, id) primary key
     * row; ids shared across kinds can no longer pick an arbitrary record. Streams audit rows
     * instead of one correlated tenant-wide subquery per column per event.
     */
    private void backfillAudits(Connection connection) throws SQLException {
        try (PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT event_id,tenant_id,resource_id,payload FROM"
                                        + " platform_audit_events");
                PreparedStatement exact =
                        connection.prepareStatement(
                                "SELECT case_id,scope_city,scope_station FROM platform_records"
                                        + " WHERE tenant_id=? AND record_id=? AND kind=?");
                // Legacy rows without a kind keep the old behaviour, made deterministic.
                PreparedStatement untyped =
                        connection.prepareStatement(
                                "SELECT case_id,scope_city,scope_station FROM platform_records"
                                        + " WHERE tenant_id=? AND record_id=? ORDER BY CASE WHEN"
                                        + " kind='case' THEN 0 ELSE 1 END,kind LIMIT 1");
                PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE platform_audit_events SET"
                                        + " case_id=?,scope_city=?,scope_station=? WHERE"
                                        + " event_id=?")) {
            query.setFetchSize(1);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Object kind = JsonUtil.fromJson(rows.getString("payload"), MAP).get("kind");
                    PreparedStatement lookup = kind == null ? untyped : exact;
                    lookup.setString(1, rows.getString("tenant_id"));
                    lookup.setString(2, rows.getString("resource_id"));
                    if (kind != null) lookup.setString(3, kind.toString());
                    try (ResultSet target = lookup.executeQuery()) {
                        if (!target.next()) continue;
                        update.setString(1, target.getString(1));
                        update.setString(2, target.getString(2));
                        update.setString(3, target.getString(3));
                        update.setString(4, rows.getString("event_id"));
                        update.executeUpdate();
                    }
                }
            }
        }
    }

    private void backfill(Connection connection, boolean cases) throws SQLException {
        String sql =
                "SELECT tenant_id,kind,record_id,payload FROM platform_records WHERE kind"
                        + (cases ? "='case'" : "<>'case'");
        try (PreparedStatement query = connection.prepareStatement(sql);
                PreparedStatement update =
                        connection.prepareStatement(
                                String.join(
                                        "",
                                        "UPDATE platform_records SET payload=?,",
                                        "case_id=?,order_id=?,complaint_type=?,",
                                        "record_status=?,record_state=?,scope_city=?,scope_station=?",
                                        " WHERE tenant_id=? AND kind=? AND record_id=?"))) {
            query.setFetchSize(1);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String tenant = rows.getString("tenant_id");
                    String kind = rows.getString("kind");
                    String id = rows.getString("record_id");
                    Map<String, Object> data =
                            new LinkedHashMap<>(JsonUtil.fromJson(rows.getString("payload"), MAP));
                    String caseId = cases ? id : text(data, "case_id");
                    Map<String, Object> parent =
                            caseId.isEmpty() || cases
                                    ? Map.of()
                                    : parent(connection, tenant, caseId);
                    Object content = data.remove("content_base64");
                    if ("material".equals(kind) && content instanceof String base64) {
                        try (PreparedStatement body =
                                connection.prepareStatement(
                                        "INSERT INTO platform_material_content"
                                                + " (tenant_id,material_id,content_base64) VALUES"
                                                + " (?,?,?)")) {
                            body.setString(1, tenant);
                            body.setString(2, id);
                            body.setString(3, base64);
                            body.executeUpdate();
                        }
                    }
                    update.setString(1, JsonUtil.toJson(data));
                    update.setString(2, caseId);
                    update.setString(3, fallback(data, parent, "order_id"));
                    update.setString(4, fallback(data, parent, "complaint_type"));
                    update.setString(5, text(data, "status"));
                    update.setString(6, text(data, "state"));
                    update.setString(7, fallback(data, parent, "city_code"));
                    update.setString(8, fallback(data, parent, "station_id"));
                    update.setString(9, tenant);
                    update.setString(10, kind);
                    update.setString(11, id);
                    update.executeUpdate();
                }
            }
        }
    }

    private Map<String, Object> parent(Connection connection, String tenant, String id)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT payload FROM platform_records WHERE tenant_id=? AND kind='case' AND"
                                + " record_id=?")) {
            query.setString(1, tenant);
            query.setString(2, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? JsonUtil.fromJson(rows.getString(1), MAP) : Map.of();
            }
        }
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }

    private static String fallback(
            Map<String, Object> data, Map<String, Object> parent, String key) {
        return data.get(key) == null ? text(parent, key) : text(data, key);
    }
}
