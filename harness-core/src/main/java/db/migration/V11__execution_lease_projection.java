package db.migration;

import com.delivery.harness.common.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Projects execution lease and fence so heartbeats renew one column instead of the payload. The
 * backfill parses JSON in Java (portable across H2/PostgreSQL) and streams one payload at a time.
 */
public class V11__execution_lease_projection extends BaseJavaMigration {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (Statement ddl = connection.createStatement()) {
            ddl.execute(
                    "ALTER TABLE platform_records ADD COLUMN lease_until TIMESTAMP(6) WITH TIME"
                            + " ZONE");
            ddl.execute(
                    "ALTER TABLE platform_records ADD COLUMN record_fence VARCHAR(160) NOT NULL"
                            + " DEFAULT ''");
        }
        try (PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT tenant_id,record_id,payload FROM platform_records WHERE"
                                        + " kind='execution'");
                PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE platform_records SET lease_until=?,record_fence=? WHERE"
                                        + " tenant_id=? AND kind='execution' AND record_id=?")) {
            // Flyway runs this inside a transaction, so PostgreSQL honours the cursor fetch size.
            query.setFetchSize(1);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> data = JsonUtil.fromJson(rows.getString("payload"), MAP);
                    Object lease = data.get("lease_until");
                    Object fence = data.get("fence");
                    if (lease == null && fence == null) continue;
                    update.setObject(
                            1,
                            lease == null
                                    ? null
                                    : Instant.parse(lease.toString())
                                            .truncatedTo(ChronoUnit.MICROS)
                                            .atOffset(ZoneOffset.UTC),
                            Types.TIMESTAMP_WITH_TIMEZONE);
                    update.setString(2, fence == null ? "" : fence.toString());
                    update.setString(3, rows.getString("tenant_id"));
                    update.setString(4, rows.getString("record_id"));
                    update.executeUpdate();
                }
            }
        }
    }
}
