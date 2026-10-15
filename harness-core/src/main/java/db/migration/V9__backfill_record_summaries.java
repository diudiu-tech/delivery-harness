package db.migration;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.RecordSummaries;
import com.fasterxml.jackson.core.type.TypeReference;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** One-time portable backfill; only one full legacy payload is materialized at a time. */
public class V9__backfill_record_summaries extends BaseJavaMigration {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void migrate(Context context) throws SQLException {
        try (PreparedStatement query =
                        context.getConnection()
                                .prepareStatement(
                                        "SELECT tenant_id,kind,record_id,payload FROM"
                                            + " platform_records");
                PreparedStatement update =
                        context.getConnection()
                                .prepareStatement(
                                        "UPDATE platform_records SET summary_payload=? WHERE"
                                            + " tenant_id=? AND kind=? AND record_id=?")) {
            query.setFetchSize(1);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String kind = rows.getString("kind");
                    Map<String, Object> data = JsonUtil.fromJson(rows.getString("payload"), MAP);
                    update.setString(1, JsonUtil.toJson(RecordSummaries.of(kind, data)));
                    update.setString(2, rows.getString("tenant_id"));
                    update.setString(3, kind);
                    update.setString(4, rows.getString("record_id"));
                    update.executeUpdate();
                }
            }
        }
    }
}
