package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.quality.KnowledgeService;
import com.delivery.harness.platform.quality.QualityData;
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

import java.util.Map;

@RestController
@RequestMapping("/api/v2")
@Validated
public class KnowledgeVersionController extends QualityAccess {
    private final KnowledgeService knowledge;

    public KnowledgeVersionController(PlatformStore store, KnowledgeService knowledge) {
        super(store);
        this.knowledge = knowledge;
    }

    @GetMapping("/knowledge/versions")
    public Map<String, Object> knowledge(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = read(request);
        return dbPage(actor, KnowledgeService.KIND, limit, cursor, e -> allowed(actor, e.data()));
    }

    @GetMapping("/knowledge/versions/{id}")
    public PlatformStore.Entity knowledge(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        return resource(read(request), KnowledgeService.KIND, id);
    }

    @PostMapping("/knowledge/versions/preview")
    public Map<String, Object> preview(
            HttpServletRequest request,
            @Valid @RequestBody KnowledgeRequests.KnowledgeRequest payload) {
        Map<String, Object> body = payload.toMap();
        PrincipalAccess.get(request).requireRole("POLICY_EDITOR", "QUALITY", "ADMIN");
        return knowledge.preview(body);
    }

    @PostMapping("/knowledge/versions")
    public PlatformStore.Entity createKnowledge(
            HttpServletRequest request,
            @Valid @RequestBody KnowledgeRequests.KnowledgeRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_EDITOR", "QUALITY", "ADMIN");
        authorizeCreateScope(actor, body);
        return knowledge.create(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/knowledge/versions/{id}/{action:review|publish|withdraw}")
    public PlatformStore.Entity transitionKnowledge(
            HttpServletRequest request,
            @PathVariable @Size(max = 100) String id,
            @PathVariable String action,
            @Valid @RequestBody QualityRequests.LifecycleRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "QUALITY", "ADMIN");
        resource(actor, KnowledgeService.KIND, id);
        return knowledge.transition(
                actor.tenantId(),
                id,
                actor.userId(),
                version(body),
                action,
                QualityData.text(body, "reason", ""));
    }

    @PostMapping({"/knowledge/search", "/knowledge/versions/search"})
    public Map<String, Object> search(
            HttpServletRequest request,
            @Valid @RequestBody KnowledgeRequests.SearchRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = read(request);
        actor.authorizeScope(
                QualityData.text(body, "city_code", null),
                QualityData.text(body, "station_id", null));
        return knowledge.search(actor.tenantId(), body);
    }

    @GetMapping("/knowledge/citations/{id}")
    public Map<String, Object> citation(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        AuthContext.Principal actor = read(request);
        Map<String, Object> result = knowledge.citation(actor.tenantId(), id);
        authorizePublicationScope(actor, result);
        return result;
    }
}
