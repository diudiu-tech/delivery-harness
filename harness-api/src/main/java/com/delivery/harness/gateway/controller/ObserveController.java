package com.delivery.harness.gateway.controller;

import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.observe.feedback.FeedbackCollector;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/observe")
@RequiredArgsConstructor
@Validated
public class ObserveController {

    private final TraceService traceService;
    private final MetricsService metricsService;
    private final FeedbackCollector feedbackCollector;

    @GetMapping("/trace/{traceId}")
    public ResponseEntity<HarnessResponse<TraceService.TraceRecord>> getTrace(@PathVariable String traceId) {
        return traceService.getTrace(traceId)
                .map(trace -> ResponseEntity.ok(HarnessResponse.success(trace)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(HarnessResponse.error(404, "Trace not found")));
    }

    @GetMapping("/traces")
    public HarnessResponse<List<TraceService.TraceRecord>> getRecentTraces(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return HarnessResponse.success(traceService.getRecentTraces(limit));
    }

    @GetMapping("/metrics")
    public HarnessResponse<Map<String, Object>> getMetrics() {
        return HarnessResponse.success(metricsService.getSnapshot());
    }

    @PostMapping("/feedback")
    public HarnessResponse<FeedbackCollector.FeedbackRecord> submitFeedback(
            @RequestBody FeedbackCollector.FeedbackRequest request) {
        return HarnessResponse.success(feedbackCollector.submit(request));
    }

    @GetMapping("/feedback")
    public HarnessResponse<List<FeedbackCollector.FeedbackRecord>> listFeedback() {
        return HarnessResponse.success(feedbackCollector.findAll());
    }
}
