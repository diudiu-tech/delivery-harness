package com.delivery.harness.gateway.platform.casework;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.CaseFilters;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v2")
public class CaseController {
    private final CaseService cases;
    private final PlatformStore store;

    public CaseController(CaseService cases, PlatformStore store) {
        this.cases = cases;
        this.store = store;
    }

    @PostMapping("/cases")
    public ResponseEntity<?> create(
            @Valid @RequestBody CaseRequests.Create input, HttpServletRequest request) {
        return ResponseEntity.status(201)
                .body(CaseService.view(cases.create(PrincipalAccess.get(request), input.toMap())));
    }

    @GetMapping("/cases")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(name = "order_id", defaultValue = "") String orderId,
            @RequestParam(name = "risk_level", defaultValue = "") String riskLevel,
            @RequestParam(name = "complaint_type", defaultValue = "") String complaintType,
            @RequestParam(defaultValue = "") String assignee,
            @RequestParam(name = "city_code", defaultValue = "") String cityCode,
            @RequestParam(name = "station_id", defaultValue = "") String stationId,
            HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        actor.requireRole("AGENT", "OPERATOR", "REVIEWER", "SUPERVISOR", "AUDITOR");
        CaseFilters filters =
                new CaseFilters(
                        status, orderId, riskLevel, complaintType, assignee, cityCode, stationId);
        PlatformStore.Page page = store.caseQueue(actor, filters, cursor, limit);
        return Map.of(
                "items",
                page.items().stream().map(CaseService::view).toList(),
                "cursor",
                page.cursor());
    }

    @GetMapping("/cases/{id}")
    public Map<String, Object> detail(@PathVariable String id, HttpServletRequest request) {
        return Redaction.map(cases.detail(PrincipalAccess.get(request), id));
    }

    @PostMapping("/cases/{id}/evidence")
    public ResponseEntity<?> evidence(
            @PathVariable String id,
            @Valid @RequestBody CaseRequests.Material input,
            HttpServletRequest request) {
        return ResponseEntity.status(201)
                .body(
                        CaseService.publicMaterial(
                                cases.addEvidence(
                                        PrincipalAccess.get(request), id, input.toMap())));
    }

    @PostMapping("/cases/{id}/close")
    public Map<String, Object> close(
            @PathVariable String id,
            @Valid @RequestBody CaseRequests.Close input,
            HttpServletRequest request) {
        return CaseService.view(cases.close(PrincipalAccess.get(request), id, input.toMap()));
    }

    @PostMapping("/materials/{id}/review")
    public Map<String, Object> verify(
            @PathVariable String id,
            @Valid @RequestBody CaseRequests.MaterialReview input,
            HttpServletRequest request) {
        return CaseService.publicMaterial(
                cases.reviewMaterial(PrincipalAccess.get(request), id, input.toMap()));
    }

    @GetMapping("/materials/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id, HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        var material = store.require(actor.tenantId(), "material", id);
        cases.require(actor, CaseService.text(material.data(), "case_id"));
        byte[] bytes = store.materialContent(actor.tenantId(), id);
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=material-" + id)
                .contentType(
                        MediaType.parseMediaType(CaseService.text(material.data(), "mime_type")))
                .body(bytes);
    }

    @GetMapping("/reviewers")
    public Map<String, Object> reviewers(
            @RequestParam("case_id") String caseId, HttpServletRequest request) {
        return Map.of("items", cases.reviewers(PrincipalAccess.get(request), caseId));
    }

    @GetMapping("/reviews")
    public Map<String, Object> reviews(
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        actor.requireRole("REVIEWER", "SUPERVISOR", "AUDITOR");
        var page = store.scopedPage(actor, "review", null, Set.of(), null, cursor, limit);
        return Map.of(
                "items",
                page.items().stream().map(CaseService::view).toList(),
                "cursor",
                page.cursor());
    }

    @PostMapping("/reviews/{id}/claim")
    public Map<String, Object> claim(
            @PathVariable String id,
            @Valid @RequestBody CaseRequests.Claim input,
            HttpServletRequest request) {
        return CaseService.view(
                cases.claim(PrincipalAccess.get(request), id, input.expectedVersion()));
    }

    @PostMapping("/reviews/{id}/actions")
    public Map<String, Object> action(
            @PathVariable String id,
            @Valid @RequestBody CaseRequests.Action input,
            HttpServletRequest request) {
        return CaseService.view(cases.act(PrincipalAccess.get(request), id, input.toMap()));
    }
}
