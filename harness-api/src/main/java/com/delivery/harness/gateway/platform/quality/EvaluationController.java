package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.quality.QualityEvalService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2")
@Validated
public class EvaluationController extends QualityAccess {
    private final QualityEvalService eval;

    public EvaluationController(PlatformStore store, QualityEvalService eval) {
        super(store);
        this.eval = eval;
    }

    @GetMapping("/eval/datasets")
    public Map<String, Object> datasets(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return dbPage(
                actor, QualityEvalService.DATASET, limit, cursor, d -> datasetAllowed(actor, d));
    }

    @GetMapping("/eval/datasets/{id}")
    public PlatformStore.Entity dataset(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        PlatformStore.Entity entity =
                store.require(actor.tenantId(), QualityEvalService.DATASET, id);
        QualityEvalService.authorizeDataset(actor, entity);
        return entity;
    }

    @PostMapping("/eval/datasets")
    public PlatformStore.Entity createDataset(
            HttpServletRequest request,
            @Valid @RequestBody EvaluationRequests.DatasetRequest payload) {
        return eval.createDataset(PrincipalAccess.get(request), payload.toMap());
    }

    @GetMapping("/eval/runs")
    public Map<String, Object> runs(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return dbPage(
                actor,
                QualityEvalService.RUN,
                limit,
                cursor,
                r -> {
                    try {
                        QualityEvalService.authorizeRunSummary(actor, r);
                        return true;
                    } catch (PlatformException e) {
                        if (e.status() == 404) {
                            return false;
                        }
                        throw e;
                    }
                });
    }

    @PostMapping("/eval/runs")
    public ResponseEntity<PlatformStore.Entity> run(
            HttpServletRequest request,
            @Valid @RequestBody EvaluationRequests.EvalRunRequest payload) {
        PlatformStore.Entity entity = eval.start(PrincipalAccess.get(request), payload.toMap());
        return ResponseEntity.accepted()
                .header("Location", "/api/v2/eval/runs/" + entity.id())
                .body(entity);
    }

    @GetMapping("/eval/runs/{id}")
    public PlatformStore.Entity run(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return eval.require(actor, id);
    }

    @GetMapping("/eval/runs/{id}/results")
    public Map<String, Object> results(
            HttpServletRequest request,
            @PathVariable @Size(max = 100) String id,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        eval.require(actor, id);
        return dbPage(actor, QualityEvalService.RESULT + ":" + id, limit, cursor, result -> true);
    }

    @PostMapping("/eval/runs/{id}/cancel")
    public PlatformStore.Entity cancel(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        return eval.cancel(PrincipalAccess.get(request), id);
    }

    @PostMapping("/eval/runs/{id}/replay")
    public PlatformStore.Entity replay(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        return eval.replay(PrincipalAccess.get(request), id);
    }

    @PostMapping("/eval/runs/{id}/retry")
    public PlatformStore.Entity retry(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        return eval.retry(PrincipalAccess.get(request), id);
    }

    @GetMapping("/eval/runs/{id}/compare/{candidateId}")
    public Map<String, Object> compare(
            HttpServletRequest request,
            @PathVariable @Size(max = 100) String id,
            @PathVariable @Size(max = 100) String candidateId) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return eval.compare(actor, id, candidateId);
    }

    protected static boolean datasetAllowed(
            AuthContext.Principal actor, PlatformStore.Entity entity) {
        try {
            QualityEvalService.authorizeDataset(actor, entity);
            return true;
        } catch (PlatformException e) {
            return false;
        }
    }
}
