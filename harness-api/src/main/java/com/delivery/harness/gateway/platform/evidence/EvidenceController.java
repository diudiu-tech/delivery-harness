package com.delivery.harness.gateway.platform.evidence;
import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.quality.KnowledgeService;
import com.delivery.harness.platform.store.PlatformStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v2/evidence")
public class EvidenceController {
    private final PlatformStore store;private final CaseService cases;private final KnowledgeService knowledge;
    public EvidenceController(PlatformStore store,CaseService cases,KnowledgeService knowledge){this.store=store;this.cases=cases;this.knowledge=knowledge;}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id,HttpServletRequest request){
        var actor=PrincipalAccess.get(request);actor.requireRole("AGENT","OPERATOR","REVIEWER","SUPERVISOR","QUALITY","AUDITOR");
        if(id.contains(":")){Map<String,Object> citation=knowledge.citation(actor.tenantId(),id);
            List<?> cities=(List<?>)citation.getOrDefault("city_codes",List.of());List<?> stations=(List<?>)citation.getOrDefault("station_ids",List.of());
            if(!cities.isEmpty() && !actor.cityCodes().contains("*") && cities.stream().noneMatch(actor.cityCodes()::contains))throw new com.delivery.harness.platform.store.PlatformException(404,"NOT_FOUND","Citation outside authorized scope");
            if(!stations.isEmpty() && !actor.stationIds().contains("*") && stations.stream().noneMatch(actor.stationIds()::contains))throw new com.delivery.harness.platform.store.PlatformException(404,"NOT_FOUND","Citation outside authorized scope");
            return Redaction.map(citation);
        }
        var snapshot=store.require(actor.tenantId(),"order_snapshot",id);cases.require(actor,CaseService.text(snapshot.data(),"case_id"));
        return Redaction.map(CaseService.view(snapshot));
    }
}
