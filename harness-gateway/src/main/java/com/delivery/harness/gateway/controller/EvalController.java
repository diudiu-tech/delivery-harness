package com.delivery.harness.gateway.controller;

import com.delivery.harness.common.dto.EvalCase;
import com.delivery.harness.common.dto.EvalResult;
import com.delivery.harness.common.dto.EvalRun;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.eval.casemanager.EvalCaseManager;
import com.delivery.harness.eval.evaluator.OfflineEvaluator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalCaseManager caseManager;
    private final OfflineEvaluator evaluator;

    @PostMapping("/cases")
    public HarnessResponse<String> addCase(@Valid @RequestBody EvalCase evalCase) {
        caseManager.save(evalCase);
        return HarnessResponse.success(evalCase.getCaseId());
    }

    @GetMapping("/cases")
    public HarnessResponse<List<EvalCase>> listCases(@RequestParam(required = false) String scenario) {
        List<EvalCase> cases = scenario != null ? caseManager.findByScenario(scenario) : caseManager.findAll();
        return HarnessResponse.success(cases);
    }

    @PostMapping("/run")
    public HarnessResponse<EvalRun> startRun(@Valid @RequestBody RunRequest request) {
        EvalRun run = evaluator.startRun(request.getCaseIds(), request.getModelVersion(), request.getPromptVersion());
        return HarnessResponse.success(run);
    }

    @GetMapping("/run/{runId}")
    public ResponseEntity<HarnessResponse<EvalRun>> getRun(@PathVariable String runId) {
        return evaluator.getRun(runId)
                .map(run -> ResponseEntity.ok(HarnessResponse.success(run)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(HarnessResponse.error(404, "Run not found")));
    }

    @GetMapping("/run/{runId}/results")
    public HarnessResponse<List<EvalResult>> getResults(@PathVariable String runId) {
        return HarnessResponse.success(evaluator.getResults(runId));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunRequest {
        @NotEmpty
        @Size(max = 100)
        private List<@NotBlank String> caseIds;
        private String modelVersion;
        private String promptVersion;
    }
}
