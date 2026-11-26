package db.migration;

import com.delivery.harness.common.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** Keep legacy cases searchable without rewriting a previously applied migration. */
public class V6__backfill_case_queue_filter_projections extends BaseJavaMigration {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void migrate(Context context) throws SQLException {
        try (PreparedStatement query =
                        context.getConnection()
                                .prepareStatement(
                                        "SELECT tenant_id,record_id,payload FROM platform_records"
                                                + " WHERE kind='case'");
                ResultSet rows = query.executeQuery();
                PreparedStatement update =
                        context.getConnection()
                                .prepareStatement(
                                        "UPDATE platform_records SET risk_level=?,assignee=? WHERE"
                                                + " tenant_id=? AND kind='case' AND record_id=?")) {
            while (rows.next()) {
                Map<String, Object> data = JsonUtil.fromJson(rows.getString("payload"), MAP);
                update.setString(1, text(data, "risk_level"));
                update.setString(2, text(data, "assignee"));
                update.setString(3, rows.getString("tenant_id"));
                update.setString(4, rows.getString("record_id"));
                update.executeUpdate();
            }
        }
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }
}
