package com.delivery.harness.gateway.platform.execution;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.execution.ExecutionService;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v2/executions")
public class ExecutionController {
    private final ExecutionService executions;
    private final PlatformStore store;

    public ExecutionController(ExecutionService executions, PlatformStore store) {
        this.executions = executions;
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<?> submit(
            @Valid @RequestBody ExecutionRequest input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        return ResponseEntity.accepted()
                .body(
                        ExecutionService.publicView(
                                executions.submit(
                                        PrincipalAccess.get(request), input.toMap(), key)));
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        actor.requireRole("AGENT", "OPERATOR", "REVIEWER", "SUPERVISOR", "QUALITY", "AUDITOR");
        var page = store.scopedPage(actor, "execution", null, Set.of(), null, cursor, limit);
        return Map.of(
                "items",
                page.items().stream().map(ExecutionService::publicView).toList(),
                "cursor",
                page.cursor());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        return ExecutionService.publicView(executions.require(PrincipalAccess.get(request), id));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, HttpServletRequest request) {
        return ExecutionService.publicView(executions.cancel(PrincipalAccess.get(request), id));
    }

    @GetMapping("/queue-status")
    public Map<String, Object> queue(HttpServletRequest request) {
        PrincipalAccess.get(request).requireRole("ADMIN", "QUALITY");
        return executions.queueStatus();
    }
}
