package com.delivery.harness.platform;
import com.delivery.harness.platform.store.PlatformStore;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;

public final class PlatformTestDatabase {
    private PlatformTestDatabase() {}
    public static DataSource source(String url) {return new DriverManagerDataSource(url,"sa","");}
    public static PlatformStore memory() {DataSource source=source("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");migrate(source);return new PlatformStore(source);}
    public static void migrate(DataSource source) {
        try(Connection c=source.getConnection();Statement s=c.createStatement()) {
            Path schema=Path.of("../harness-api/src/main/resources/db/migration/V1__platform_records.sql");
            if(!Files.exists(schema))schema=Path.of("harness-api/src/main/resources/db/migration/V1__platform_records.sql");
            try(var files=Files.list(schema.getParent())){for(Path migration:files.filter(path -> path.toString().endsWith(".sql")).sorted().toList())for(String sql:Files.readString(migration).split(";"))if(!sql.isBlank())s.execute(sql);}
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
