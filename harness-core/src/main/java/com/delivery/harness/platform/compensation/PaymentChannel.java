package com.delivery.harness.platform.compensation;

import java.util.Map;
import java.math.BigDecimal;
import java.time.Instant;

public interface PaymentChannel {
    record Result(String status, String externalId, String note) {}
    record Eligibility(String orderId, String currency, String orderStatus, BigDecimal remainingRefund,
            BigDecimal remainingCompensation, String version, Instant observedAt) {}
    /** A real channel must verify current order state and remaining funds before reservation. */
    default Eligibility eligibility(String tenantId, String orderId) {
        if (!synthetic()) throw new IllegalStateException("Channel does not implement eligibility verification");
        return new Eligibility(orderId, "CNY", "DELIVERED", new BigDecimal("50.00"),
                new BigDecimal("50.00"), "synthetic-only", Instant.now());
    }
    Result submit(String compensationId, Map<String, Object> approved);
    Result query(String compensationId, Map<String, Object> approved);
    String name();
    boolean synthetic();
}
