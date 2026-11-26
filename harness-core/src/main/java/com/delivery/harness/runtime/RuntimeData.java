package com.delivery.harness.runtime;

import com.delivery.harness.common.util.JsonUtil;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small runtime value operations that do not depend on platform services or persistence. */
public final class RuntimeData {
    private RuntimeData() {}

    public static String hash(Object value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    JsonUtil.toJson(canonical(value))
                                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> target = new TreeMap<>();
            source.forEach((key, item) -> target.put(String.valueOf(key), canonical(item)));
            return target;
        }
        if (value instanceof Iterable<?> source) {
            List<Object> target = new ArrayList<>();
            source.forEach(item -> target.add(canonical(item)));
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
        String result = text(value, key, "").trim();
        if (result.isEmpty() || result.length() > maxLength) {
            throw new IllegalArgumentException(
                    key + " must contain 1.." + maxLength + " characters");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    public static List<Map<String, Object>> maps(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Runtime snapshots must contain a list of objects");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Runtime snapshot entry must be an object");
            }
            result.add(copy(map(item)));
        }
        return result;
    }

    public static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    public static boolean applicable(
            Map<String, Object> data, String city, String station, Instant asOf) {
        List<String> cities = strings(data.get("city_codes"));
        List<String> stations = strings(data.get("station_ids"));
        boolean from =
                !data.containsKey("effective_from")
                        || !asOf.isBefore(Instant.parse(data.get("effective_from").toString()));
        boolean to =
                !data.containsKey("effective_to")
                        || asOf.isBefore(Instant.parse(data.get("effective_to").toString()));
        return (cities.isEmpty() || cities.contains(city))
                && (stations.isEmpty() || stations.contains(station))
                && from
                && to;
    }
}
