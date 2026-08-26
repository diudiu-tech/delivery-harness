package com.delivery.harness.observe.feedback;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FeedbackCollector {

    private final Map<String, FeedbackRecord> feedbackStore = new ConcurrentHashMap<>();
    private final Deque<String> feedbackOrder = new ConcurrentLinkedDeque<>();
    private final int maxFeedback;

    public FeedbackCollector(@Value("${harness.observe.max-feedback:1000}") int maxFeedback) {
        this.maxFeedback = Math.max(1, maxFeedback);
    }

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
        feedbackOrder.addLast(record.getFeedbackId());
        while (feedbackOrder.size() > maxFeedback) {
            String oldest = feedbackOrder.pollFirst();
            if (oldest != null) {
                feedbackStore.remove(oldest);
            }
        }
        log.info("Feedback submitted: id={}", record.getFeedbackId());
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
        @NotBlank
        @Size(max = 64)
        private String traceId;
        @Size(max = 32)
        private String rating;
        @Size(max = 2_000)
        private String correction;
        @Size(max = 2_000)
        private String comment;
        @Size(max = 128)
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
