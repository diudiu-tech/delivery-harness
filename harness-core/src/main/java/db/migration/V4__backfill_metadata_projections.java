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
 * dialect.
 */
public class V4__backfill_metadata_projections extends BaseJavaMigration {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        backfill(connection, true);
        backfill(connection, false);
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE platform_audit_events SET case_id=COALESCE((SELECT r.case_id FROM"
                                + " platform_records r WHERE"
                                + " r.tenant_id=platform_audit_events.tenant_id AND"
                                + " r.record_id=platform_audit_events.resource_id LIMIT"
                                + " 1),''),scope_city=COALESCE((SELECT r.scope_city FROM"
                                + " platform_records r WHERE"
                                + " r.tenant_id=platform_audit_events.tenant_id AND"
                                + " r.record_id=platform_audit_events.resource_id LIMIT"
                                + " 1),''),scope_station=COALESCE((SELECT r.scope_station FROM"
                                + " platform_records r WHERE"
                                + " r.tenant_id=platform_audit_events.tenant_id AND"
                                + " r.record_id=platform_audit_events.resource_id LIMIT 1),'')")) {
            update.executeUpdate();
        }
    }

    private void backfill(Connection connection, boolean cases) throws SQLException {
        String sql =
                "SELECT tenant_id,kind,record_id,payload FROM platform_records WHERE kind"
                        + (cases ? "='case'" : "<>'case'");
        try (PreparedStatement query = connection.prepareStatement(sql);
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String tenant = rows.getString("tenant_id");
                String kind = rows.getString("kind");
                String id = rows.getString("record_id");
                Map<String, Object> data =
                        new LinkedHashMap<>(JsonUtil.fromJson(rows.getString("payload"), MAP));
                String caseId = cases ? id : text(data, "case_id");
                Map<String, Object> parent =
                        caseId.isEmpty() || cases ? Map.of() : parent(connection, tenant, caseId);
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
                try (PreparedStatement update =
                        connection.prepareStatement(
                                String.join(
                                        "",
                                        "UPDATE platform_records SET payload=?,",
                                        "case_id=?,order_id=?,complaint_type=?,",
                                        "record_status=?,record_state=?,scope_city=?,scope_station=?",
                                        " WHERE tenant_id=? AND kind=? AND record_id=?"))) {
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
