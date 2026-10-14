package com.delivery.harness.platform;

import com.delivery.harness.platform.store.PlatformStore;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import javax.sql.DataSource;

public final class PlatformTestDatabase {
    private PlatformTestDatabase() {}

    public static DataSource source(String url) {
        return new DriverManagerDataSource(url, "sa", "");
    }

    public static PlatformStore memory() {
        DataSource source = source("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        migrate(source);
        return new PlatformStore(source);
    }

    public static void migrate(DataSource source) {
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }
}
