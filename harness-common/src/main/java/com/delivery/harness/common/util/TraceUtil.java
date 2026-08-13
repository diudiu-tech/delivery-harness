package com.delivery.harness.common.util;

import java.util.UUID;

public final class TraceUtil {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceUtil() {}

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
