package com.delivery.harness.observe.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TraceService {

    private final Map<String, TraceRecord> traceStore = new ConcurrentHashMap<>();

    public void recordTrace(TraceRecord trace) {
        traceStore.put(trace.getTraceId(), trace);
    }

    public Optional<TraceRecord> getTrace(String traceId) {
        return Optional.ofNullable(traceStore.get(traceId));
    }

    public List<TraceRecord> getRecentTraces(int limit) {
        return traceStore.values().stream()
                .sorted(Comparator.comparing(TraceRecord::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceRecord {
        private String traceId;
        private String scenario;
        private Map<String, Object> input;
        private Map<String, Object> output;
        private List<TraceSpan> spans;
        private Long totalDurationMs;
        private Integer totalTokens;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceSpan {
        private String spanId;
        private String spanType;
        private String name;
        private Long durationMs;
        private Map<String, Object> attributes;
    }
}
