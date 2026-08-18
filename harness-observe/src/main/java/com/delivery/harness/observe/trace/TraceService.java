package com.delivery.harness.observe.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * In-memory ring of recent request traces.
 *
 * <p>Bounded. The previous implementation was an unbounded ConcurrentHashMap,
 * which is a memory leak in any process that stays up — and it never grew,
 * because {@code recordTrace} had no callers at all. The three
 * {@code /api/v1/observe} read endpoints consequently returned empty forever
 * while appearing to offer tracing.
 *
 * <p>AgentOrchestrator now records one trace per workflow execution, so these
 * endpoints return data. Traces are still lost on restart;
 * this is a debugging aid for a single process, not an observability
 * backend. Export to a real collector before relying on it.
 */
@Slf4j
@Service
public class TraceService {

    private final Map<String, TraceRecord> traceStore;

    public TraceService(@Value("${harness.observe.max-traces:500}") int maxTraces) {
        int capacity = Math.max(1, maxTraces);
        this.traceStore = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TraceRecord> eldest) {
                return size() > capacity;
            }
        });
    }

    public void recordTrace(TraceRecord trace) {
        if (trace == null || trace.getTraceId() == null) {
            return;
        }
        if (trace.getCreatedAt() == null) {
            trace.setCreatedAt(LocalDateTime.now());
        }
        traceStore.put(trace.getTraceId(), trace);
    }

    public Optional<TraceRecord> getTrace(String traceId) {
        return Optional.ofNullable(traceStore.get(traceId));
    }

    public List<TraceRecord> getRecentTraces(int limit) {
        List<TraceRecord> snapshot;
        synchronized (traceStore) {
            snapshot = new ArrayList<>(traceStore.values());
        }
        return snapshot.stream()
                .sorted(Comparator.comparing(
                        TraceRecord::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(0, limit))
                .collect(Collectors.toList());
    }

    public int size() {
        return traceStore.size();
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
