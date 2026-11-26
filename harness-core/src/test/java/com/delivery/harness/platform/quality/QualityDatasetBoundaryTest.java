package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.tool.order.OrderFixtures;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

class QualityDatasetBoundaryTest {
    @Test
    void blindSplitGuardDoesNotMissOverlapBeyondTheFirstThousandDatasets() {
        var store = PlatformTestDatabase.memory();
        for (int id = 0; id < 1001; id++) {
            store.create(
                    "tenant",
                    QualityEvalService.DATASET,
                    "dataset-" + String.format("%04d", id),
                    Map.of(
                            "split",
                            "REAL_LABELLED",
                            "samples",
                            List.of(Map.of("input", Map.of("order_id", "other-" + id)))),
                    "author",
                    "TEST_DATASET");
        }
        store.create(
                "tenant",
                QualityEvalService.DATASET,
                "z-overlap",
                Map.of(
                        "split",
                        "REAL_LABELLED",
                        "samples",
                        List.of(Map.of("input", Map.of("order_id", "DEMO-001")))),
                "author",
                "TEST_DATASET");
        var service = new QualityEvalService(store, null, null, null, null, "test");
        try {
            var actor =
                    new AuthContext.Principal(
                            "tenant", "author", Set.of("QUALITY"), Set.of("*"), Set.of("*"));
            var evidence = OrderFixtures.build("DEMO-001", LocalDateTime.of(2026, 1, 1, 12, 0));
            var request =
                    Map.<String, Object>of(
                            "split",
                            "FROZEN_BLIND",
                            "samples",
                            List.of(
                                    Map.of(
                                            "scenario",
                                            "abnormal_order_analysis",
                                            "input",
                                            Map.of("order_id", "DEMO-001"),
                                            "evidence_order",
                                            JsonUtil.toMap(evidence))));
            assertThrows(
                    IllegalArgumentException.class, () -> service.createDataset(actor, request));
        } finally {
            service.shutdown();
        }
    }
}
