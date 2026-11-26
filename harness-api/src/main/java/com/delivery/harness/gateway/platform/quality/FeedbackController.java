package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.quality.StructuredFeedbackService;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v2")
@Validated
public class FeedbackController extends QualityAccess {
    private final StructuredFeedbackService feedback;

    public FeedbackController(PlatformStore store, StructuredFeedbackService feedback) {
        super(store);
        this.feedback = feedback;
    }

    @GetMapping("/feedback")
    public Map<String, Object> feedback(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = read(request);
        PlatformStore.Page page =
                store.scopedSummaryPage(
                        actor, StructuredFeedbackService.KIND, null, Set.of(), null, cursor, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.items());
        result.put("next_cursor", page.cursor());
        return result;
    }

    @GetMapping("/feedback/{id}")
    public PlatformStore.Entity detail(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        return feedback.require(PrincipalAccess.get(request), id);
    }

    @PostMapping("/feedback")
    public PlatformStore.Entity feedback(
            HttpServletRequest request,
            @Valid @RequestBody FeedbackRequests.FeedbackRequest payload) {
        return feedback.create(PrincipalAccess.get(request), payload.toMap());
    }

    @PostMapping("/feedback/{id}/status")
    public PlatformStore.Entity status(
            HttpServletRequest request,
            @PathVariable @Size(max = 100) String id,
            @Valid @RequestBody FeedbackRequests.FeedbackStatusRequest payload) {
        Map<String, Object> body = payload.toMap();
        return feedback.transition(PrincipalAccess.get(request), id, version(body), body);
    }
}
