package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.quality.PolicyService;
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
public class PolicyController extends QualityAccess {
    private final PolicyService policies;

    public PolicyController(PlatformStore store, PolicyService policies) {
        super(store);
        this.policies = policies;
    }

    @GetMapping("/policies/versions")
    public Map<String, Object> policies(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = read(request);
        return dbPage(actor, PolicyService.KIND, limit, cursor, e -> allowed(actor, e.data()));
    }

    @GetMapping("/policies/versions/{id}")
    public PlatformStore.Entity policy(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        return resource(read(request), PolicyService.KIND, id);
    }

    @PostMapping("/policies/versions")
    public PlatformStore.Entity createPolicy(
            HttpServletRequest request, @Valid @RequestBody PolicyRequests.PolicyRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_EDITOR", "ADMIN");
        authorizeCreateScope(actor, body);
        return policies.create(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/policies/versions/{id}/validate")
    public Map<String, Object> validatePolicy(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_EDITOR", "POLICY_PUBLISHER", "QUALITY", "ADMIN");
        resource(actor, PolicyService.KIND, id);
        return policies.validate(actor.tenantId(), id);
    }

    @PostMapping("/policies/versions/{id}/publish")
    public PlatformStore.Entity publishPolicy(
            HttpServletRequest request,
            @PathVariable @Size(max = 100) String id,
            @Valid @RequestBody QualityRequests.LifecycleRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        resource(actor, PolicyService.KIND, id);
        return policies.publish(actor.tenantId(), id, actor.userId(), version(body));
    }

    @PostMapping("/policies/versions/{id}/withdraw")
    public PlatformStore.Entity withdrawPolicy(
            HttpServletRequest request,
            @PathVariable @Size(max = 100) String id,
            @Valid @RequestBody QualityRequests.LifecycleRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        resource(actor, PolicyService.KIND, id);
        return policies.withdraw(
                actor.tenantId(),
                id,
                actor.userId(),
                version(body),
                QualityData.required(body, "reason", 2000));
    }
}
