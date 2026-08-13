package com.delivery.harness.observe.audit;

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
public class AuditService {

    private final Map<String, AuditLog> auditStore = new ConcurrentHashMap<>();

    public void log(String action, String operator, String traceId, Map<String, Object> details) {
        AuditLog entry = AuditLog.builder()
                .auditId(UUID.randomUUID().toString())
                .action(action)
                .operator(operator)
                .traceId(traceId)
                .details(details)
                .createdAt(LocalDateTime.now())
                .build();
        auditStore.put(entry.getAuditId(), entry);
        log.info("Audit: action={}, operator={}, traceId={}", action, operator, traceId);
    }

    public List<AuditLog> findByTraceId(String traceId) {
        return auditStore.values().stream()
                .filter(a -> traceId.equals(a.getTraceId()))
                .collect(Collectors.toList());
    }

    public List<AuditLog> getRecent(int limit) {
        return auditStore.values().stream()
                .sorted(Comparator.comparing(AuditLog::getCreatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLog {
        private String auditId;
        private String action;
        private String operator;
        private String traceId;
        private Map<String, Object> details;
        private LocalDateTime createdAt;
    }
}
