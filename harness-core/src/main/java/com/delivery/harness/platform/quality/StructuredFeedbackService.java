package com.delivery.harness.platform.quality;

import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StructuredFeedbackService {
    public static final String KIND = "feedback";
    private final PlatformStore store;

    public PlatformStore.Entity create(AuthContext.Principal actor, Map<String, Object> request) {
        actor.requireRole("AGENT", "OPERATOR", "REVIEWER", "QUALITY", "ADMIN");
        String caseId = QualityData.required(request, "case_id", 100);
        String decisionId = QualityData.required(request, "decision_id", 100);
        authorizedCase(actor, caseId);
        PlatformStore.Entity decision = store.require(actor.tenantId(), "decision", decisionId);
        if (!caseId.equals(decision.data().get("case_id"))) {
            throw new PlatformException(404, "NOT_FOUND", "Decision not found in case");
        }
        if (!(request.get("decision_version") instanceof Number version)
                || version.longValue() != decision.version()) {
            throw PlatformException.conflict(
                    "Feedback must reference an existing exact decision version");
        }
        String category = QualityData.required(request, "category", 64);
        if (!Set.of(
                        "ATTRIBUTION_ERROR",
                        "RULE_ERROR",
                        "AMOUNT_ERROR",
                        "EVIDENCE_ERROR",
                        "MESSAGE_ERROR",
                        "USABILITY")
                .contains(category)) {
            throw new IllegalArgumentException("Unsupported feedback category");
        }
        Map<String, Object> correction = QualityData.map(request.get("correction"));
        QualityData.required(correction, "reason_code", 100);
        if (!"USABILITY".equals(category)) {
            String path = QualityData.required(correction, "field_path", 100);
            if (!correction.containsKey("original_value")
                    || !correction.containsKey("suggested_value")) {
                throw new IllegalArgumentException(
                        "Structured correction requires original_value and suggested_value");
            }
            Object original = decision.data();
            for (String field : path.split("\\.")) {
                if (!(original instanceof Map<?, ?> values) || !values.containsKey(field)) {
                    throw new IllegalArgumentException(
                            "Correction field does not exist in the referenced decision");
                }
                original = values.get(field);
            }
            boolean same = Objects.equals(original, correction.get("original_value"));
            if (original instanceof Number || "amount".equals(path)) {
                try {
                    same =
                            original == null
                                    ? correction.get("original_value") == null
                                    : new BigDecimal(original.toString())
                                                    .compareTo(
                                                            new BigDecimal(
                                                                    String.valueOf(
                                                                            correction.get(
                                                                                    "original_value"))))
                                            == 0;
                } catch (RuntimeException e) {
                    same = false;
                }
            }
            if (!same) {
                throw PlatformException.conflict(
                        "Correction original_value differs from the immutable referenced decision");
            }
        }
        List<String> evidenceRefs = QualityData.strings(correction.get("evidence_refs"));
        for (String reference : evidenceRefs) {
            if (reference.equals(decision.data().get("evidence_snapshot_id"))) {
                continue;
            }
            PlatformStore.Entity material =
                    store.get(actor.tenantId(), "material", reference).orElse(null);
            if (material == null || !caseId.equals(material.data().get("case_id"))) {
                throw new PlatformException(
                        404, "NOT_FOUND", "Feedback evidence not found in authorized case");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("case_id", caseId);
        data.put("decision_id", decisionId);
        data.put("decision_version", decision.version());
        data.put("execution_id", decision.data().get("execution_id"));
        data.put("runtime_config_id", decision.data().get("runtime_config_id"));
        data.put("policy_version", decision.data().get("policy_version"));
        data.put("evidence_snapshot_id", decision.data().get("evidence_snapshot_id"));
        data.put("category", category);
        data.put("correction", correction);
        data.put("submitted_by", actor.userId());
        data.put("status", "PENDING_VERIFICATION");
        return store.transact(
                tx -> {
                    PlatformStore.Entity lockedCase = tx.lock(actor.tenantId(), "case", caseId);
                    actor.authorizeScope(
                            QualityData.text(lockedCase.data(), "city_code", null),
                            QualityData.text(lockedCase.data(), "station_id", null));
                    tx.require(actor.tenantId(), "decision", decisionId);
                    return tx.create(
                            actor.tenantId(),
                            KIND,
                            UUID.randomUUID().toString(),
                            data,
                            actor.userId(),
                            "FEEDBACK_SUBMITTED");
                });
    }

    public PlatformStore.Entity require(AuthContext.Principal actor, String id) {
        actor.requireRole("AGENT", "OPERATOR", "REVIEWER", "QUALITY", "AUDITOR", "ADMIN");
        PlatformStore.Entity entity = store.require(actor.tenantId(), KIND, id);
        authorizedCase(actor, QualityData.required(entity.data(), "case_id", 100));
        return entity;
    }

    private PlatformStore.Entity authorizedCase(AuthContext.Principal actor, String id) {
        PlatformStore.Entity caseRecord = store.require(actor.tenantId(), "case", id);
        actor.authorizeScope(
                QualityData.text(caseRecord.data(), "city_code", null),
                QualityData.text(caseRecord.data(), "station_id", null));
        return caseRecord;
    }

    public List<PlatformStore.Entity> list(AuthContext.Principal actor) {
        actor.requireRole("AGENT", "OPERATOR", "REVIEWER", "QUALITY", "AUDITOR", "ADMIN");
        return store.scopedPage(actor, KIND, null, Set.of(), null, "", 100).items();
    }

    /** A supplied regression reference must be fully readable before it can authorize a label. */
    private String validatedRegression(
            AuthContext.Principal actor, String runId, String feedbackId) {
        PlatformStore.Entity run = store.require(actor.tenantId(), QualityEvalService.RUN, runId);
        RuntimeConfigService.authorizeRead(
                actor, QualityData.map(run.data().get("resolved_config")));
        QualityEvalService.authorizeDataset(
                actor,
                new PlatformStore.Entity(
                        run.id(),
                        actor.tenantId(),
                        "eval_dataset",
                        1,
                        run.createdAt(),
                        run.updatedAt(),
                        QualityData.map(run.data().get("dataset_snapshot"))));
        if (!"COMPLETED".equals(run.data().get("status"))
                || !Boolean.TRUE.equals(run.data().get("hard_gate_passed"))) {
            throw new IllegalArgumentException(
                    "Regression verification requires a completed passing labelled evaluation");
        }
        if (PolicyService.ruleMaps(
                        QualityData.map(run.data().get("dataset_snapshot")).get("samples"))
                .stream()
                .noneMatch(sample -> feedbackId.equals(sample.get("source_feedback_id")))) {
            throw new IllegalArgumentException(
                    "Regression report must include this verified feedback sample");
        }
        return runId;
    }

    public PlatformStore.Entity transition(
            AuthContext.Principal actor, String id, long version, Map<String, Object> request) {
        actor.requireRole("QUALITY", "ADMIN");
        PlatformStore.Entity entity = require(actor, id);
        String status = QualityData.required(request, "status", 64);
        String reason = QualityData.required(request, "reason", 2000);
        String current = entity.data().get("status").toString();
        boolean allowed =
                switch (current) {
                    case "PENDING_VERIFICATION" -> Set.of("CONFIRMED", "REJECTED").contains(status);
                    case "CONFIRMED" -> "LINKED_FIX".equals(status);
                    case "LINKED_FIX" -> "REGRESSION_VERIFIED".equals(status);
                    default -> false;
                };
        if (!allowed) {
            throw PlatformException.conflict("Invalid feedback lifecycle transition");
        }
        if ("CONFIRMED".equals(status)
                && actor.userId().equals(entity.data().get("submitted_by"))) {
            throw new PlatformException(
                    403, "SEPARATION_OF_DUTIES", "Feedback requires an independent verifier");
        }
        Map<String, Object> data = QualityData.copy(entity.data());
        if ("LINKED_FIX".equals(status)) {
            data.put("issue_ref", QualityData.required(request, "issue_ref", 256));
        }
        if ("REGRESSION_VERIFIED".equals(status)) {
            data.put(
                    "eval_run_id",
                    validatedRegression(
                            actor, QualityData.required(request, "eval_run_id", 100), id));
        }
        data.put("status", status);
        data.put("verification_reason", reason);
        data.put("verified_by", actor.userId());
        return store.transact(
                tx -> {
                    PlatformStore.Entity lockedCase =
                            tx.lock(
                                    actor.tenantId(),
                                    "case",
                                    entity.data().get("case_id").toString());
                    actor.authorizeScope(
                            QualityData.text(lockedCase.data(), "city_code", null),
                            QualityData.text(lockedCase.data(), "station_id", null));
                    return tx.update(
                            actor.tenantId(),
                            KIND,
                            id,
                            version,
                            data,
                            actor.userId(),
                            "FEEDBACK_" + status);
                });
    }
}
