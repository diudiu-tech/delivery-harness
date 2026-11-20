package com.delivery.harness.gateway.controller;

import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/observe")
@RequiredArgsConstructor
@Validated
public class ObserveController {

    private final TraceService traceService;
    private final MetricsService metricsService;

    @GetMapping("/traces/{traceId}")
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
}
