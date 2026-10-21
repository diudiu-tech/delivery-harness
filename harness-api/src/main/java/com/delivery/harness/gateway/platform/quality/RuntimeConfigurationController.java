package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.quality.KnowledgeService;
import com.delivery.harness.platform.quality.PolicyService;
import com.delivery.harness.platform.quality.QualityData;
import com.delivery.harness.platform.quality.RuntimeConfigService;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.runtime.RuntimeVisibility;

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
public class RuntimeConfigurationController extends QualityAccess {
    private final RuntimeConfigService configs;

    public RuntimeConfigurationController(PlatformStore store, RuntimeConfigService configs) {
        super(store);
        this.configs = configs;
    }

    @GetMapping("/runtime-configs")
    public Map<String, Object> configs(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = read(request);
        return dbPage(
                actor,
                RuntimeConfigService.KIND,
                limit,
                cursor,
                c -> runtimeAllowed(actor, c.data()));
    }

    @GetMapping("/runtime-configs/{id}")
    public PlatformStore.Entity config(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        AuthContext.Principal actor = read(request);
        PlatformStore.Entity entity =
                store.require(actor.tenantId(), RuntimeConfigService.KIND, id);
        RuntimeConfigService.authorizeRead(actor, entity.data());
        return entity;
    }

    @PostMapping("/runtime-configs")
    public PlatformStore.Entity createConfig(
            HttpServletRequest request,
            @Valid @RequestBody RuntimeRequests.RuntimeRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "ADMIN");
        resource(actor, PolicyService.KIND, QualityData.required(body, "policy_version", 100));
        for (String id : QualityData.strings(body.get("knowledge_versions"))) {
            resource(actor, KnowledgeService.KIND, id);
        }
        return configs.create(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/runtime-configs/{id}/probe")
    public Map<String, Object> probe(
            HttpServletRequest request, @PathVariable @Size(max = 100) String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "ADMIN");
        config(request, id);
        return configs.probe(actor.tenantId(), id);
    }

    @GetMapping("/runtime-routes")
    public Map<String, Object> routes(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 100) String cursor) {
        AuthContext.Principal actor = read(request);
        return dbPage(actor, "runtime_route", limit, cursor, route -> allowed(actor, route.data()));
    }

    @PostMapping("/runtime-routes")
    public PlatformStore.Entity route(
            HttpServletRequest request, @Valid @RequestBody RuntimeRequests.RouteRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        authorizeCreateScope(actor, body);
        config(request, QualityData.required(body, "primary_config_id", 100));
        config(request, QualityData.required(body, "fallback_config_id", 100));
        return configs.createRoute(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/runtime-configs/{id}/publish")
    public PlatformStore.Entity publishConfig(
            HttpServletRequest request,
            @PathVariable @Size(max = 100) String id,
            @Valid @RequestBody RuntimeRequests.RuntimePublishRequest payload) {
        Map<String, Object> body = payload.toMap();
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        config(request, id);
        PlatformStore.Entity report =
                store.require(
                        actor.tenantId(),
                        "eval_run",
                        QualityData.required(body, "eval_run_id", 100));
        RuntimeConfigService.authorizeRead(
                actor, QualityData.map(report.data().get("resolved_config")));
        for (Map<String, Object> sample :
                PolicyService.ruleMaps(
                        QualityData.map(report.data().get("dataset_snapshot")).get("samples"))) {
            Map<String, Object> evidence = QualityData.map(sample.get("evidence_order"));
            actor.authorizeScope(
                    QualityData.text(evidence, "cityCode", null),
                    QualityData.text(evidence, "stationId", null));
        }
        return configs.publish(actor.tenantId(), id, actor.userId(), version(body), report);
    }

    private static boolean runtimeAllowed(AuthContext.Principal actor, Map<String, Object> config) {
        return RuntimeVisibility.canReadSummary(config, actor.cityCodes(), actor.stationIds());
    }
}
