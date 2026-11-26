package com.delivery.harness.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.platform.store.LegacyV3ChecksumCallback;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

@SpringBootTest(
        properties = {"spring.datasource.url=jdbc:h2:mem:flyway-callbacks;DB_CLOSE_DELAY=-1"})
class FlywayCallbackWiringTest {
    @Autowired Flyway flyway;

    @Test
    void applicationFlywayAcceptsTheKnownPreCorrectionV3OnH2Files() {
        assertTrue(
                Arrays.stream(flyway.getConfiguration().getCallbacks())
                        .anyMatch(LegacyV3ChecksumCallback.class::isInstance));
    }
}
