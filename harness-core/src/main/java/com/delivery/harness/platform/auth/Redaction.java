package com.delivery.harness.platform.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Presentation allowlist boundary; durable raw business snapshots remain access controlled. */
public final class Redaction {
    private static final Set<String> PRIVATE =
            Set.of(
                    "merchantName",
                    "merchantId",
                    "riderName",
                    "riderId",
                    "userId",
                    "pickupAddress",
                    "deliveryAddress",
                    "merchant_name",
                    "rider_name",
                    "user_id",
                    "phone",
                    "mobile",
                    "address",
                    "model_raw_output",
                    "raw_model_output",
                    "llm_raw_output",
                    "modelRawOutput",
                    "rawModelOutput",
                    "llmRawOutput",
                    "content_base64",
                    "api_key",
                    "token");
    private static final Set<String> ORDER_FIELDS =
            Set.of(
                    "orderId",
                    "status",
                    "stationId",
                    "cityCode",
                    "createTime",
                    "acceptTime",
                    "arriveShopTime",
                    "pickupTime",
                    "deliveryTime",
                    "expectedDeliveryTime",
                    "estimatedDurationSeconds",
                    "actualDurationSeconds",
                    "orderAmount",
                    "refundableBalance",
                    "deliveryFee",
                    "isAbnormal",
                    "abnormalType",
                    "extraInfo");
    private static final Set<String> PUBLIC_EXTRA_FIELDS =
            Set.of("weather", "source_type", "historical_context_available");

    private Redaction() {}

    public static Object safe(Object value) {
        if (value instanceof Map<?, ?> source) {
            boolean order = source.containsKey("orderId") && source.containsKey("status");
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach(
                    (rawKey, item) -> {
                        String key = rawKey.toString();
                        if (PRIVATE.contains(key) || order && !ORDER_FIELDS.contains(key)) {
                            return;
                        }
                        if (order && "extraInfo".equals(key) && item instanceof Map<?, ?> extras) {
                            Map<String, Object> clean = new LinkedHashMap<>();
                            for (String allowed : PUBLIC_EXTRA_FIELDS) {
                                if (extras.containsKey(allowed)) {
                                    clean.put(allowed, safe(extras.get(allowed)));
                                }
                            }
                            result.put(key, clean);
                        } else {
                            result.put(key, safe(item));
                        }
                    });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Redaction::safe).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> value) {
        return (Map<String, Object>) safe(value);
    }
}
