package com.delivery.harness.platform.store;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * V3 was corrected in place because PostgreSQL can never have applied the original script. H2
 * file databases may have; for H2 only, accept exactly the original checksum as the corrected one.
 * Any other V3 checksum still fails validation. See the pilot runbook, section 5.
 */
@Component
public class LegacyV3ChecksumCallback implements Callback {
    public static final int ORIGINAL_V3_CHECKSUM = 122558595;
    public static final int CORRECTED_V3_CHECKSUM = -691553654;
    private static final Logger LOG = LoggerFactory.getLogger(LegacyV3ChecksumCallback.class);

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try {
            if (!"H2".equals(connection.getMetaData().getDatabaseProductName())) return;
            Configuration configuration = context.getConfiguration();
            String schema = schema(configuration);
            String table = configuration.getTable();
            try (ResultSet tables =
                    connection.getMetaData().getTables(null, schema, table, null)) {
                if (!tables.next()) return;
            }
            String qualified = (schema == null ? "" : quote(schema) + ".") + quote(table);
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE "
                                    + qualified
                                    + " SET \"checksum\"=? WHERE \"version\"='3' AND"
                                    + " \"checksum\"=?")) {
                update.setInt(1, CORRECTED_V3_CHECKSUM);
                update.setInt(2, ORIGINAL_V3_CHECKSUM);
                if (update.executeUpdate() > 0) {
                    LOG.warn("Accepted the known pre-correction V3 checksum on H2");
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to check the V3 migration checksum", failure);
        }
    }

    @Override
    public String getCallbackName() {
        return "legacy-v3-checksum";
    }

    private static String schema(Configuration configuration) {
        if (configuration.getDefaultSchema() != null) return configuration.getDefaultSchema();
        String[] schemas = configuration.getSchemas();
        return schemas.length == 0 ? null : schemas[0];
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
