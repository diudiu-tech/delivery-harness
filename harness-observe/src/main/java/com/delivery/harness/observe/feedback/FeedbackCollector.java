package com.delivery.harness.observe.feedback;

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
public class FeedbackCollector {

    private final Map<String, FeedbackRecord> feedbackStore = new ConcurrentHashMap<>();

    public FeedbackRecord submit(FeedbackRequest request) {
        FeedbackRecord record = FeedbackRecord.builder()
                .feedbackId(UUID.randomUUID().toString())
                .traceId(request.getTraceId())
                .rating(request.getRating())
                .correction(request.getCorrection())
                .comment(request.getComment())
                .submittedBy(request.getSubmittedBy())
                .createdAt(LocalDateTime.now())
                .build();
        feedbackStore.put(record.getFeedbackId(), record);
        log.info("Feedback submitted: id={}, traceId={}, rating={}", record.getFeedbackId(), request.getTraceId(), request.getRating());
        return record;
    }

    public List<FeedbackRecord> findByTraceId(String traceId) {
        return feedbackStore.values().stream()
                .filter(f -> traceId.equals(f.getTraceId()))
                .collect(Collectors.toList());
    }

    public List<FeedbackRecord> findAll() {
        return new ArrayList<>(feedbackStore.values());
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackRequest {
        private String traceId;
        private String rating;
        private String correction;
        private String comment;
        private String submittedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackRecord {
        private String feedbackId;
        private String traceId;
        private String rating;
        private String correction;
        private String comment;
        private String submittedBy;
        private LocalDateTime createdAt;
    }
}
