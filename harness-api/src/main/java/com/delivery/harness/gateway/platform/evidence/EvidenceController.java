package com.delivery.harness.gateway.platform.evidence;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.casework.CitationProvider;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/evidence")
public class EvidenceController {
    private final PlatformStore store;
    private final CaseService cases;
    private final ObjectProvider<CitationProvider> knowledge;

    public EvidenceController(
            PlatformStore store, CaseService cases, ObjectProvider<CitationProvider> knowledge) {
        this.store = store;
        this.cases = cases;
        this.knowledge = knowledge;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        actor.requireRole("AGENT", "OPERATOR", "REVIEWER", "SUPERVISOR", "QUALITY", "AUDITOR");
        if (id.contains(":")) {
            CitationProvider provider = knowledge.getIfAvailable();
            if (provider == null)
                throw new PlatformException(
                        404, "NOT_FOUND", "Versioned citation provider is unavailable");
            Map<String, Object> citation = provider.citation(actor.tenantId(), id);
            List<?> cities = (List<?>) citation.getOrDefault("city_codes", List.of());
            List<?> stations = (List<?>) citation.getOrDefault("station_ids", List.of());
            if (!cities.isEmpty()
                    && !actor.cityCodes().contains("*")
                    && cities.stream().noneMatch(actor.cityCodes()::contains))
                throw new PlatformException(404, "NOT_FOUND", "Citation outside authorized scope");
            if (!stations.isEmpty()
                    && !actor.stationIds().contains("*")
                    && stations.stream().noneMatch(actor.stationIds()::contains))
                throw new PlatformException(404, "NOT_FOUND", "Citation outside authorized scope");
            return Redaction.map(citation);
        }
        var snapshot = store.require(actor.tenantId(), "order_snapshot", id);
        cases.require(actor, CaseService.text(snapshot.data(), "case_id"));
        return Redaction.map(CaseService.view(snapshot));
    }
}
