package com.delivery.harness.observe.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class MetricsService {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totalLatency = new ConcurrentHashMap<>();

    public void incrementCounter(String metric) {
        counters.computeIfAbsent(metric, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordLatency(String metric, long durationMs) {
        totalLatency.computeIfAbsent(metric, k -> new AtomicLong(0)).addAndGet(durationMs);
        incrementCounter(metric + "_count");
    }

    public long getCounter(String metric) {
        AtomicLong counter = counters.get(metric);
        return counter != null ? counter.get() : 0;
    }

    public double getAvgLatency(String metric) {
        long total = totalLatency.getOrDefault(metric, new AtomicLong(0)).get();
        long count = getCounter(metric + "_count");
        return count > 0 ? (double) total / count : 0.0;
    }

    public Map<String, Object> getSnapshot() {
        Map<String, Object> snapshot = new ConcurrentHashMap<>();
        counters.forEach((k, v) -> snapshot.put(k, v.get()));
        totalLatency.forEach((k, v) -> snapshot.put(k + "_total_ms", v.get()));
        return snapshot;
    }
}
