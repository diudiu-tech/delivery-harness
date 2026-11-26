package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.platform.store.LegacyV3ChecksumCallback;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;

import javax.sql.DataSource;

/** H2 file databases may already hold the pre-correction V3; they must keep starting. */
class LegacyV3ChecksumTest {
    @Test
    void callbackConstantsMatchTheOriginalAndCorrectedScripts() {
        DataSource source = PlatformTestDatabase.source("jdbc:h2:mem:checksums;DB_CLOSE_DELAY=-1");
        assertEquals(
                LegacyV3ChecksumCallback.CORRECTED_V3_CHECKSUM,
                v3(Flyway.configure().dataSource(source).locations("classpath:db/migration")));
        assertEquals(
                LegacyV3ChecksumCallback.ORIGINAL_V3_CHECKSUM,
                v3(Flyway.configure().dataSource(source).locations("classpath:db/original-v3")));
    }

    @Test
    void h2FileDatabaseMigratedWithOriginalV3KeepsStarting(@TempDir Path directory)
            throws Exception {
        DataSource source =
                PlatformTestDatabase.source("jdbc:h2:file:" + directory.resolve("pilot"));
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/original-v3")
                .ignoreMigrationPatterns("*:missing")
                .load()
                .migrate();
        // Without the narrowly scoped callback the corrected script fails validation.
        assertThrows(
                FlywayValidateException.class,
                () ->
                        Flyway.configure()
                                .dataSource(source)
                                .locations("classpath:db/migration")
                                .load()
                                .migrate());

        PlatformTestDatabase.migrate(source);

        assertEquals(LegacyV3ChecksumCallback.CORRECTED_V3_CHECKSUM, appliedV3(source));
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO platform_records(tenant_id,kind,record_id,record_version,"
                            + "created_at,updated_at,payload) VALUES ('t','note','n',1,"
                            + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'{}')");
            try (ResultSet rows =
                    statement.executeQuery("SELECT queue_due_at FROM platform_records")) {
                assertTrue(rows.next());
                assertEquals(
                        Instant.parse("9999-01-01T00:00:00Z"),
                        rows.getObject(1, OffsetDateTime.class).toInstant());
            }
        }
        // Repeated startups stay valid.
        PlatformTestDatabase.migrate(source);
    }

    @Test
    void anyOtherV3ChecksumStillFailsValidation(@TempDir Path directory) throws Exception {
        DataSource source =
                PlatformTestDatabase.source("jdbc:h2:file:" + directory.resolve("tampered"));
        PlatformTestDatabase.migrate(source);
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE \"flyway_schema_history\" SET \"checksum\"=12345 WHERE"
                            + " \"version\"='3'");
        }
        assertThrows(FlywayValidateException.class, () -> PlatformTestDatabase.migrate(source));
        assertEquals(12345, appliedV3(source));
    }

    private static int v3(org.flywaydb.core.api.configuration.FluentConfiguration configuration) {
        MigrationInfo[] all =
                configuration.ignoreMigrationPatterns("*:missing").load().info().all();
        return Arrays.stream(all)
                .filter(info -> "3".equals(info.getVersion().getVersion()))
                .findFirst()
                .orElseThrow()
                .getChecksum();
    }

    private static int appliedV3(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT \"checksum\" FROM \"flyway_schema_history\" WHERE"
                                        + " \"version\"='3'")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
