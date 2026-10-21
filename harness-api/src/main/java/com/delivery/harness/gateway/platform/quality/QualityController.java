package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.quality.KnowledgeService;
import com.delivery.harness.platform.quality.PolicyService;
import com.delivery.harness.platform.quality.QualityData;
import com.delivery.harness.platform.quality.QualityEvalService;
import com.delivery.harness.platform.quality.RuntimeConfigService;
import com.delivery.harness.platform.quality.StructuredFeedbackService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class QualityController {
    private final PlatformStore store;
    private final PolicyService policies;
    private final KnowledgeService knowledge;
    private final RuntimeConfigService configs;
    private final QualityEvalService eval;
    private final StructuredFeedbackService feedback;

    @GetMapping("/policies/versions")
    public Map<String, Object> policies(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit,
                                        @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = read(request);
        policies.ensureDefault(actor.tenantId());
        return dbPage(actor, PolicyService.KIND, limit, cursor, e -> allowed(actor, e.data()));
    }

    @GetMapping("/policies/versions/{id}")
    public PlatformStore.Entity policy(HttpServletRequest request, @PathVariable String id) {
        return resource(read(request), PolicyService.KIND, id);
    }

    @PostMapping("/policies/versions")
    public PlatformStore.Entity createPolicy(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_EDITOR", "ADMIN");
        authorizeCreateScope(actor, body);
        return policies.create(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/policies/versions/{id}/validate")
    public Map<String, Object> validatePolicy(HttpServletRequest request, @PathVariable String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_EDITOR", "POLICY_PUBLISHER", "QUALITY", "ADMIN");
        resource(actor, PolicyService.KIND, id);
        return policies.validate(actor.tenantId(), id);
    }

    @PostMapping("/policies/versions/{id}/publish")
    public PlatformStore.Entity publishPolicy(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        resource(actor, PolicyService.KIND, id);
        return policies.publish(actor.tenantId(), id, actor.userId(), version(body));
    }

    @PostMapping("/policies/versions/{id}/withdraw")
    public PlatformStore.Entity withdrawPolicy(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        resource(actor, PolicyService.KIND, id);
        return policies.withdraw(actor.tenantId(), id, actor.userId(), version(body), QualityData.required(body, "reason", 2000));
    }

    @GetMapping("/knowledge/versions")
    public Map<String, Object> knowledge(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit,
                                         @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = read(request);
        return dbPage(actor, KnowledgeService.KIND, limit, cursor, e -> allowed(actor, e.data()));
    }

    @GetMapping("/knowledge/versions/{id}")
    public PlatformStore.Entity knowledge(HttpServletRequest request, @PathVariable String id) {
        return resource(read(request), KnowledgeService.KIND, id);
    }

    @PostMapping("/knowledge/versions/preview")
    public Map<String, Object> preview(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        PrincipalAccess.get(request).requireRole("POLICY_EDITOR", "QUALITY", "ADMIN");
        return knowledge.preview(body);
    }

    @PostMapping("/knowledge/versions")
    public PlatformStore.Entity createKnowledge(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_EDITOR", "QUALITY", "ADMIN");
        authorizeCreateScope(actor, body);
        return knowledge.create(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/knowledge/versions/{id}/{action:review|publish|withdraw}")
    public PlatformStore.Entity transitionKnowledge(HttpServletRequest request, @PathVariable String id, @PathVariable String action,
                                                    @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "QUALITY", "ADMIN");
        resource(actor, KnowledgeService.KIND, id);
        return knowledge.transition(actor.tenantId(), id, actor.userId(), version(body), action, QualityData.text(body, "reason", ""));
    }

    @PostMapping({"/knowledge/search", "/knowledge/versions/search"})
    public Map<String, Object> search(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = read(request);
        actor.authorizeScope(QualityData.text(body, "city_code", null), QualityData.text(body, "station_id", null));
        return knowledge.search(actor.tenantId(), body);
    }

    @GetMapping("/knowledge/citations/{id}")
    public Map<String, Object> citation(HttpServletRequest request, @PathVariable String id) {
        AuthContext.Principal actor = read(request);
        Map<String, Object> result = knowledge.citation(actor.tenantId(), id);
        authorizePublicationScope(actor, result);
        return result;
    }

    @GetMapping("/runtime-configs")
    public Map<String, Object> configs(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit,
                                       @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = read(request);
        configs.ensureDefault(actor.tenantId());
        return dbPage(actor, RuntimeConfigService.KIND, limit, cursor, c -> allowed(actor, QualityData.map(c.data().get("policy_scope"))));
    }

    @GetMapping("/runtime-configs/{id}")
    public PlatformStore.Entity config(HttpServletRequest request, @PathVariable String id) {
        AuthContext.Principal actor = read(request);
        PlatformStore.Entity entity = store.require(actor.tenantId(), RuntimeConfigService.KIND, id);
        authorizePublicationScope(actor, QualityData.map(entity.data().get("policy_scope")));
        return entity;
    }

    @PostMapping("/runtime-configs")
    public PlatformStore.Entity createConfig(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "ADMIN");
        resource(actor, PolicyService.KIND, QualityData.required(body, "policy_version", 100));
        for (String id : QualityData.strings(body.get("knowledge_versions"))) {
            resource(actor, KnowledgeService.KIND, id);
        }
        return configs.create(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/runtime-configs/{id}/probe")
    public Map<String, Object> probe(HttpServletRequest request, @PathVariable String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "ADMIN");
        config(request, id);
        return configs.probe(actor.tenantId(), id);
    }

    @GetMapping("/runtime-routes")
    public Map<String, Object> routes(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = read(request);
        return dbPage(actor, "runtime_route", limit, cursor, route -> allowed(actor, route.data()));
    }

    @PostMapping("/runtime-routes")
    public PlatformStore.Entity route(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        authorizeCreateScope(actor, body);
        config(request, QualityData.required(body, "primary_config_id", 100));
        config(request, QualityData.required(body, "fallback_config_id", 100));
        return configs.createRoute(actor.tenantId(), actor.userId(), body);
    }

    @PostMapping("/runtime-configs/{id}/publish")
    public PlatformStore.Entity publishConfig(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("POLICY_PUBLISHER", "ADMIN");
        config(request, id);
        PlatformStore.Entity report = eval.require(actor, QualityData.required(body, "eval_run_id", 100));
        return configs.publish(actor.tenantId(), id, actor.userId(), version(body), report);
    }

    @GetMapping("/eval/datasets")
    public Map<String, Object> datasets(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit,
                                        @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        eval.ensureDefaults(actor);
        return dbPage(actor, QualityEvalService.DATASET, limit, cursor, d -> datasetAllowed(actor, d));
    }

    @GetMapping("/eval/datasets/{id}")
    public PlatformStore.Entity dataset(HttpServletRequest request, @PathVariable String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        PlatformStore.Entity entity = store.require(actor.tenantId(), QualityEvalService.DATASET, id);
        QualityEvalService.authorizeDataset(actor, entity);
        return entity;
    }

    @PostMapping("/eval/datasets")
    public PlatformStore.Entity createDataset(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return eval.createDataset(PrincipalAccess.get(request), body);
    }

    @GetMapping("/eval/runs")
    public Map<String, Object> runs(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return dbPage(actor, QualityEvalService.RUN, limit, cursor, r -> {
            try {
                eval.require(actor, r.id());
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
    public ResponseEntity<PlatformStore.Entity> run(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        PlatformStore.Entity entity = eval.start(PrincipalAccess.get(request), body);
        return ResponseEntity.accepted().header("Location", "/api/v2/eval/runs/" + entity.id()).body(entity);
    }

    @GetMapping("/eval/runs/{id}")
    public PlatformStore.Entity run(HttpServletRequest request, @PathVariable String id) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return eval.require(actor, id);
    }

    @GetMapping("/eval/runs/{id}/results")
    public Map<String, Object> results(HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return page(eval.results(actor, id), limit, cursor);
    }

    @PostMapping("/eval/runs/{id}/cancel")
    public PlatformStore.Entity cancel(HttpServletRequest request, @PathVariable String id) {
        return eval.cancel(PrincipalAccess.get(request), id);
    }

    @PostMapping("/eval/runs/{id}/replay")
    public PlatformStore.Entity replay(HttpServletRequest request, @PathVariable String id) {
        return eval.replay(PrincipalAccess.get(request), id);
    }

    @PostMapping("/eval/runs/{id}/retry")
    public PlatformStore.Entity retry(HttpServletRequest request, @PathVariable String id) {
        return eval.retry(PrincipalAccess.get(request), id);
    }

    @GetMapping("/eval/runs/{id}/compare/{candidateId}")
    public Map<String, Object> compare(HttpServletRequest request, @PathVariable String id, @PathVariable String candidateId) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("QUALITY", "AUDITOR", "ADMIN");
        return eval.compare(actor, id, candidateId);
    }

    @GetMapping("/feedback")
    public Map<String, Object> feedback(HttpServletRequest request, @RequestParam(defaultValue = "20") int limit,
                                        @RequestParam(required = false) String cursor) {
        AuthContext.Principal actor = read(request);
        return dbPage(actor, StructuredFeedbackService.KIND, limit, cursor, f -> {
            try { feedback.require(actor, f.id()); return true; }
            catch (PlatformException e) { if (e.status() == 404) return false; throw e; }
        });
    }

    @PostMapping("/feedback")
    public PlatformStore.Entity feedback(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return feedback.create(PrincipalAccess.get(request), body);
    }

    @PostMapping("/feedback/{id}/status")
    public PlatformStore.Entity status(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return feedback.transition(PrincipalAccess.get(request), id, version(body), body);
    }

    private AuthContext.Principal read(HttpServletRequest request) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole("AGENT", "OPERATOR", "REVIEWER", "POLICY_EDITOR", "POLICY_PUBLISHER", "QUALITY", "AUDITOR", "ADMIN");
        return actor;
    }

    private PlatformStore.Entity resource(AuthContext.Principal actor, String kind, String id) {
        PlatformStore.Entity entity = store.require(actor.tenantId(), kind, id);
        authorizePublicationScope(actor, entity.data());
        return entity;
    }

    private static void authorizePublicationScope(AuthContext.Principal actor, Map<String, Object> data) {
        List<String> cities = QualityData.strings(data.get("city_codes"));
        List<String> stations = QualityData.strings(data.get("station_ids"));
        // Unscoped content is visible globally within its tenant, but a scoped editor cannot publish globally.
        if ((!cities.isEmpty() && !actor.cityCodes().contains("*") && !actor.cityCodes().containsAll(cities))
                || (!stations.isEmpty() && !actor.stationIds().contains("*") && !actor.stationIds().containsAll(stations))) {
            throw new PlatformException(404, "NOT_FOUND", "Content not found in authorized scope");
        }
    }

    private static void authorizeCreateScope(AuthContext.Principal actor, Map<String, Object> data) {
        authorizePublicationScope(actor, data);
        if ((QualityData.strings(data.get("city_codes")).isEmpty() && !actor.cityCodes().contains("*"))
                || (QualityData.strings(data.get("station_ids")).isEmpty() && !actor.stationIds().contains("*"))) {
            throw new PlatformException(403, "FORBIDDEN", "Scoped authors must explicitly limit publication to authorized cities and stations");
        }
    }

    private static boolean allowed(AuthContext.Principal actor, Map<String, Object> data) {
        try {
            authorizePublicationScope(actor, data);
            return true;
        } catch (PlatformException e) {
            return false;
        }
    }

    private static List<PlatformStore.Entity> scoped(AuthContext.Principal actor, List<PlatformStore.Entity> entities) {
        return entities.stream().filter(e -> allowed(actor, e.data())).toList();
    }

    private static boolean datasetAllowed(AuthContext.Principal actor, PlatformStore.Entity entity) {
        try {
            QualityEvalService.authorizeDataset(actor, entity);
            return true;
        } catch (PlatformException e) {
            return false;
        }
    }

    private static long version(Map<String, Object> body) {
        if (!(body.get("expected_version") instanceof Number version) || version.longValue() < 1) {
            throw new IllegalArgumentException("expected_version must be a positive version");
        }
        return version.longValue();
    }

    private Map<String, Object> dbPage(AuthContext.Principal actor, String kind, int limit, String cursor,
                                        java.util.function.Predicate<PlatformStore.Entity> filter) {
        List<PlatformStore.Entity> records = store.page(actor.tenantId(), kind, cursor, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", records.stream().filter(filter).toList());
        result.put("next_cursor", records.size() == limit ? records.get(records.size() - 1).id() : null);
        return result;
    }

    private static Map<String, Object> page(List<PlatformStore.Entity> source, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be 1..100");
        }
        int from = 0;
        if (cursor != null && !cursor.isBlank()) {
            int found = -1;
            for (int i = 0; i < source.size(); i++) {
                if (source.get(i).id().equals(cursor)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                throw new IllegalArgumentException("Unknown cursor");
            }
            from = found + 1;
        }
        int to = Math.min(source.size(), from + limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", source.subList(from, to));
        result.put("next_cursor", to < source.size() ? source.get(to - 1).id() : null);
        return result;
    }
}
