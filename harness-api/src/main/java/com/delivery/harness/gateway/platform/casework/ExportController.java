package com.delivery.harness.gateway.platform.casework;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Export appends an audit receipt, so its HTTP boundary is an explicit POST. */
@RestController
public class ExportController {
    private final CaseService cases;
    private final PlatformStore store;

    public ExportController(CaseService cases, PlatformStore store) {
        this.cases = cases;
        this.store = store;
    }

    @PostMapping("/api/v2/cases/{id}/export")
    public Map<String, Object> export(@PathVariable String id, HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        actor.requireRole("OPERATOR", "SUPERVISOR", "AUDITOR");
        Map<String, Object> detail = cases.detail(actor, id);
        store.transact(
                tx -> {
                    var current = tx.lock(actor.tenantId(), "case", id);
                    CaseService.authorize(actor, current);
                    if (current.version() != ((Number) detail.get("version")).longValue()) {
                        throw PlatformException.conflict(
                                "Case changed during export; refresh and export again");
                    }
                    tx.audit(
                            actor.tenantId(),
                            actor.userId(),
                            "CASE_EXPORTED",
                            id,
                            Map.of("format", "structured_json", "version", current.version()));
                    return null;
                });
        return Redaction.map(detail);
    }
}
