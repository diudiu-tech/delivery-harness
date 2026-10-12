package com.delivery.harness.platform.quality;

import com.delivery.harness.common.util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical hashes identify the executable contents, rather than a mutable label. */
public final class QualityData {
    private QualityData() {}

    public static String hash(Object value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtil.toJson(canonical(value)).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> target = new TreeMap<>();
            source.forEach((k, v) -> target.put(String.valueOf(k), canonical(v)));
            return target;
        }
        if (value instanceof Iterable<?> source) {
            List<Object> target = new ArrayList<>();
            source.forEach(v -> target.add(canonical(v)));
            return target;
        }
        return value;
    }

    public static Map<String, Object> copy(Map<String, Object> value) {
        return new LinkedHashMap<>(JsonUtil.toMap(value));
    }

    public static String text(Map<String, Object> value, String key, String fallback) {
        Object raw = value.get(key);
        return raw == null ? fallback : raw.toString();
    }

    public static String required(Map<String, Object> value, String key, int maxLength) {
        String text = text(value, key, "").trim();
        if (text.isEmpty() || text.length() > maxLength) {
            throw new IllegalArgumentException(key + " must contain 1.." + maxLength + " characters");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }
}
