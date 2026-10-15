package com.delivery.harness.platform.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.RuntimeBoundaryException;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.eval.casemanager.EvalCaseManager;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.llm.gateway.LlmChatRequest;
import com.delivery.harness.llm.gateway.LlmClient;
import com.delivery.harness.llm.gateway.LlmGateway;
import com.delivery.harness.llm.gateway.LlmMessage;
import com.delivery.harness.llm.router.ModelRouter;
import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import com.delivery.harness.runtime.RuntimeBinding;
import com.delivery.harness.tool.compensation.CompensationRuleToolkit;
import com.delivery.harness.tool.gateway.ToolGateway;
import com.delivery.harness.tool.order.OrderFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class QualityServiceTest {
    PlatformStore store;
    RuleBaseService ruleBase;
    PolicyService policies;
    KnowledgeService knowledge;
    RuntimeConfigService configs;
    AuthContext.Principal actor =
            new AuthContext.Principal(
                    "quality-test", "author", Set.of("QUALITY", "ADMIN"), Set.of("*"), Set.of("*"));
    List<String> calledModels = new ArrayList<>();
    List<String> calledPrompts = new ArrayList<>();
    LlmClient client;

    @BeforeEach
    void setUp() {
        store = PlatformTestDatabase.memory();
        ruleBase = new RuleBaseService();
        ruleBase.save(
                RuleInfo.builder()
                        .ruleId("COMP-001")
                        .ruleName("test")
                        .ruleType("compensation")
                        .content("outdated text")
                        .priority(1)
                        .enabled(true)
                        .conditions(Map.of("complaint_type", "OVERTIME", "min_overtime_minutes", 0))
                        .actions(
                                Map.of(
                                        "amount_type",
                                        "FIXED",
                                        "amount",
                                        "5.00",
                                        "method",
                                        "COUPON",
                                        "approval_required",
                                        false))
                        .build());
        policies = new PolicyService(store, ruleBase);
        knowledge = new KnowledgeService(store, policies);
        client =
                (model, messages, tools, temperature, maxTokens) -> {
                    calledModels.add(model);
                    calledPrompts.add(
                            messages.stream()
                                    .filter(message -> "user".equals(message.getRole()))
                                    .map(LlmMessage::getContent)
                                    .collect(Collectors.joining("\n")));
                    return "{\"ok\":true}";
                };
        configs = new RuntimeConfigService(store, policies, knowledge, client);
        configs.ensureDefault(actor.tenantId());
    }

    @Test
    void policyPublishRejectsOverlapAndRequiresIndependentPublisher() {
        Map<String, Object> rule = JsonUtil.toMap(ruleBase.findById("COMP-001").orElseThrow());
        Map<String, Object> duplicate = QualityData.copy(rule);
        duplicate.put("ruleId", "COMP-002");
        var conflict =
                policies.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of("rules", List.of(rule, duplicate)));
        assertEquals(false, policies.validate(actor.tenantId(), conflict.id()).get("valid"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policies.publish(
                                actor.tenantId(), conflict.id(), "publisher", conflict.version()));
        var draft =
                policies.create(actor.tenantId(), actor.userId(), Map.of("rules", List.of(rule)));
        assertEquals(
                403,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        policies.publish(
                                                actor.tenantId(),
                                                draft.id(),
                                                actor.userId(),
                                                draft.version()))
                        .status());
        var published =
                policies.publish(actor.tenantId(), draft.id(), "publisher", draft.version());
        String text = policies.resolve(actor.tenantId(), draft.id()).get(0).getContent();
        assertTrue(text.contains("5.00 CNY"));
        assertFalse(text.contains("outdated"));
        assertThrows(
                PlatformException.class,
                () ->
                        policies.publish(
                                actor.tenantId(), draft.id(), "publisher", published.version()));
        assertEquals(
                "5.00",
                policies.resolve(actor.tenantId(), draft.id()).get(0).getActions().get("amount"));
    }

    @Test
    void invalidStructuralFormulaAndIntervalsAreRejected() {
        Map<String, Object> rule = JsonUtil.toMap(ruleBase.findById("COMP-001").orElseThrow());
        rule.put(
                "actions",
                Map.of("amount_type", "PERCENT_CAPPED", "rate", 2, "cap", -1, "method", "REFUND"));
        rule.put(
                "conditions",
                Map.of(
                        "complaint_type",
                        "OVERTIME",
                        "min_overtime_minutes",
                        31,
                        "max_overtime_minutes",
                        30));
        var draft =
                policies.create(actor.tenantId(), actor.userId(), Map.of("rules", List.of(rule)));
        Map<String, Object> report = policies.validate(actor.tenantId(), draft.id());
        assertEquals(false, report.get("valid"));
        assertTrue(report.get("errors").toString().contains("INVALID_INTERVAL"));
        assertTrue(report.get("errors").toString().contains("INVALID_AMOUNT_OR_BOUNDARY"));
    }

    @Test
    void overtimeCoverageWarningsDoNotInventZeroAmountRulesAndKeepFloorMinutes() {
        Map<String, Object> disabled = intervalRule("DISABLED", 16, 16);
        disabled.put("enabled", false);
        var draft =
                policies.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of(
                                "rules",
                                List.of(
                                        intervalRule("HIGH", 17, null),
                                        disabled,
                                        intervalRule("LOW", 0, 15))));
        Map<String, Object> report = policies.validate(actor.tenantId(), draft.id());
        assertEquals(true, report.get("valid"));
        var gaps = PolicyService.ruleMaps(report.get("coverage_gaps"));
        assertEquals(1, gaps.size());
        assertEquals(16L, gaps.get(0).get("min_overtime_minutes"));
        assertEquals(16L, gaps.get(0).get("max_overtime_minutes"));
        assertEquals("WARNING", gaps.get(0).get("severity"));
        assertEquals("OUT_OF_SCOPE", gaps.get(0).get("uncovered_decision_status"));
        policies.publish(actor.tenantId(), draft.id(), "publisher", draft.version());
        var config =
                configs.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of(
                                "model",
                                "fixed-model",
                                "prompt",
                                "safe prompt",
                                "policy_version",
                                draft.id(),
                                "deterministic_only",
                                true));
        ToolGateway gateway = new ToolGateway(10);
        new CompensationRuleToolkit(gateway, ruleBase).init();
        try (RuntimeBinding ignored =
                RuntimeBinding.bind(
                        order(),
                        null,
                        configs.resolveForEvaluation(actor.tenantId(), config.id()),
                        false)) {
            Map<String, Object> uncovered =
                    QualityData.map(
                            gateway.invoke(
                                            "compensation_rule",
                                            Map.of(
                                                    "complaint_type",
                                                    "OVERTIME",
                                                    "overtime_seconds",
                                                    16 * 60 + 59,
                                                    "order_amount",
                                                    "64.00",
                                                    "currency",
                                                    "CNY"))
                                    .getData());
            assertEquals("OUT_OF_SCOPE", uncovered.get("decision_status"));
            assertNull(uncovered.get("authoritative_amount"));
            assertTrue(((List<?>) uncovered.get("matched_rules")).isEmpty());
            assertEquals(
                    "FLOOR", QualityData.map(uncovered.get("calculation")).get("minute_rounding"));
            Map<String, Object> covered =
                    QualityData.map(
                            gateway.invoke(
                                            "compensation_rule",
                                            Map.of(
                                                    "complaint_type",
                                                    "OVERTIME",
                                                    "overtime_seconds",
                                                    17 * 60,
                                                    "order_amount",
                                                    "64.00",
                                                    "currency",
                                                    "CNY"))
                                    .getData());
            assertEquals("DETERMINED", covered.get("decision_status"));
            assertEquals(new BigDecimal("5.00"), covered.get("authoritative_amount"));
        }
    }

    @Test
    void overtimeCoverageReportsZeroAndOpenTailAndIgnoresInvalidIntervals() {
        var draft =
                policies.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of("rules", List.of(intervalRule("FINITE", 1, 15))));
        var gaps =
                PolicyService.ruleMaps(
                        policies.validate(actor.tenantId(), draft.id()).get("coverage_gaps"));
        assertEquals(2, gaps.size());
        assertEquals(0L, gaps.get(0).get("min_overtime_minutes"));
        assertEquals(0L, gaps.get(0).get("max_overtime_minutes"));
        assertEquals(16L, gaps.get(1).get("min_overtime_minutes"));
        assertTrue(gaps.get(1).containsKey("max_overtime_minutes"));
        assertNull(gaps.get(1).get("max_overtime_minutes"));
        var invalid =
                policies.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of("rules", List.of(intervalRule("INVALID", 2, 1))));
        Map<String, Object> invalidReport = policies.validate(actor.tenantId(), invalid.id());
        assertEquals(false, invalidReport.get("valid"));
        var invalidGaps = PolicyService.ruleMaps(invalidReport.get("coverage_gaps"));
        assertEquals(0L, invalidGaps.get(0).get("min_overtime_minutes"));
        assertNull(invalidGaps.get(0).get("max_overtime_minutes"));
        var complete =
                policies.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of(
                                "rules",
                                List.of(
                                        intervalRule("HIGH", 16, null),
                                        intervalRule("LOW", 0, 15))));
        assertEquals(
                List.of(), policies.validate(actor.tenantId(), complete.id()).get("coverage_gaps"));
        var overlapping =
                policies.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of(
                                "rules",
                                List.of(
                                        intervalRule("HIGH", 10, null),
                                        intervalRule("LOW", 0, 15))));
        Map<String, Object> overlapReport = policies.validate(actor.tenantId(), overlapping.id());
        assertEquals(false, overlapReport.get("valid"));
        assertEquals(List.of(), overlapReport.get("coverage_gaps"));
    }

    @Test
    void knowledgePreviewKeepsDiagnosticsButPublicationRejectsExplicitPersonalData() {
        Map<String, String> examples =
                Map.of(
                        "手机 13812345678", "POTENTIAL_PHONE_NUMBER",
                        "手机 +86 138-1234-5678", "POTENTIAL_PHONE_NUMBER",
                        "手机 １３８１２３４５６７８", "POTENTIAL_PHONE_NUMBER",
                        "邮件 person@example.com", "POTENTIAL_EMAIL_ADDRESS",
                        "证件 110101199001011234", "POTENTIAL_IDENTITY_NUMBER",
                        "姓名：张三", "POTENTIAL_NAME_OR_ADDRESS_LABEL",
                        "收货地址：北京市某街道 123 号", "POTENTIAL_NAME_OR_ADDRESS_LABEL",
                        "\"recipient_name\": \"Example Person\"",
                                "POTENTIAL_NAME_OR_ADDRESS_LABEL");
        for (var example : examples.entrySet()) {
            Map<String, Object> request =
                    Map.of("title", "个人信息诊断", "content", example.getKey(), "warnings", List.of());
            assertTrue(
                    ((List<?>) knowledge.preview(request).get("warnings"))
                            .contains(example.getValue()),
                    example.getKey());
            var draft = knowledge.create(actor.tenantId(), actor.userId(), request);
            var reviewed =
                    knowledge.transition(
                            actor.tenantId(),
                            draft.id(),
                            "reviewer",
                            draft.version(),
                            "review",
                            "reviewed");
            PlatformException failure =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    knowledge.transition(
                                            actor.tenantId(),
                                            draft.id(),
                                            "publisher",
                                            reviewed.version(),
                                            "publish",
                                            "publish"),
                            example.getKey());
            assertEquals(422, failure.status());
            assertEquals("KNOWLEDGE_CONTAINS_PERSONAL_DATA", failure.code());
            assertFalse(failure.getMessage().contains(example.getKey()));
            assertEquals(
                    "REVIEWED",
                    store.require(actor.tenantId(), KnowledgeService.KIND, draft.id())
                            .data()
                            .get("state"));
            assertEquals(
                    reviewed.version(),
                    store.require(actor.tenantId(), KnowledgeService.KIND, draft.id()).version());
        }
        Map<String, Object> titleRequest =
                Map.of("title", "person@example.com", "content", "一般参考材料");
        assertTrue(
                ((List<?>) knowledge.preview(titleRequest).get("warnings"))
                        .contains("POTENTIAL_EMAIL_ADDRESS"));
        var titleDraft = knowledge.create(actor.tenantId(), actor.userId(), titleRequest);
        var titleReviewed =
                knowledge.transition(
                        actor.tenantId(),
                        titleDraft.id(),
                        "reviewer",
                        titleDraft.version(),
                        "review",
                        "");
        assertThrows(
                PlatformException.class,
                () ->
                        knowledge.transition(
                                actor.tenantId(),
                                titleDraft.id(),
                                "publisher",
                                titleReviewed.version(),
                                "publish",
                                ""));
    }

    @Test
    void sanitizedKnowledgePublishesWithOriginalVersionHashAndOldUnsafeSnapshotsCannotRun() {
        String safe = "姓名：[已脱敏]\n收货地址：[REDACTED]\n超时参考材料";
        assertEquals(
                List.of(),
                knowledge.preview(Map.of("title", "脱敏材料", "content", safe)).get("warnings"));
        var draft =
                knowledge.create(
                        actor.tenantId(), actor.userId(), Map.of("title", "脱敏材料", "content", safe));
        var review =
                knowledge.transition(
                        actor.tenantId(), draft.id(), "reviewer", draft.version(), "review", "");
        knowledge.transition(
                actor.tenantId(), draft.id(), "publisher", review.version(), "publish", "");
        Map<String, Object> frozen = knowledge.freeze(actor.tenantId(), List.of(draft.id())).get(0);
        assertEquals(QualityData.hash(safe), frozen.get("content_hash"));
        assertEquals(draft.id(), frozen.get("version_id"));
        var unsafe =
                store.create(
                        actor.tenantId(),
                        KnowledgeService.KIND,
                        "legacy-personal-data",
                        Map.of(
                                "state",
                                "PUBLISHED",
                                "title",
                                "旧版本",
                                "content",
                                "一般内容",
                                "chunks",
                                List.of(
                                        Map.of(
                                                "title_path",
                                                "一般标题",
                                                "content",
                                                "person@example.com",
                                                "chunk_id",
                                                "legacy-personal-data:0"))),
                        "legacy",
                        "TEST");
        assertEquals(
                422,
                assertThrows(
                                PlatformException.class,
                                () -> knowledge.freeze(actor.tenantId(), List.of(unsafe.id())))
                        .status());
        assertTrue(
                PolicyService.ruleMaps(
                                knowledge
                                        .search(
                                                actor.tenantId(),
                                                Map.of("query", "person@example.com"))
                                        .get("items"))
                        .stream()
                        .noneMatch(match -> unsafe.id().equals(match.get("version_id"))));
        Map<String, Object> oldConfig =
                QualityData.copy(configs.resolve(actor.tenantId(), "runtime-default-v1"));
        oldConfig.put("knowledge_snapshot", List.of(unsafe.data()));
        assertEquals(
                "KNOWLEDGE_CONTAINS_PERSONAL_DATA",
                assertThrows(
                                RuntimeBoundaryException.class,
                                () -> RuntimeBinding.bind(order(), null, oldConfig, false))
                        .getReasonCode());
        assertNull(RuntimeBinding.current());
        assertTrue(calledModels.isEmpty());
    }

    @Test
    void runtimePromptRejectsExplicitPersonalDataBeforePersistenceOrModelUse() {
        int initial = store.list(actor.tenantId(), RuntimeConfigService.KIND).size();
        for (String prompt :
                List.of(
                        "phone 13812345678",
                        "邮件 person@example.com",
                        "证件 110101199001011234",
                        "姓名：张三",
                        "地址：北京市某街道 123 号")) {
            RuntimeBoundaryException failure =
                    assertThrows(
                            RuntimeBoundaryException.class,
                            () ->
                                    configs.create(
                                            actor.tenantId(),
                                            actor.userId(),
                                            Map.of(
                                                    "model",
                                                    "test-model",
                                                    "prompt",
                                                    prompt,
                                                    "policy_version",
                                                    "policy-default-v1")));
            assertEquals(422, failure.getCode());
            assertEquals("RUNTIME_PROMPT_CONTAINS_PERSONAL_DATA", failure.getReasonCode());
            assertFalse(failure.getMessage().contains(prompt));
        }
        assertEquals(initial, store.list(actor.tenantId(), RuntimeConfigService.KIND).size());
        Map<String, Object> oldConfig =
                QualityData.copy(configs.resolve(actor.tenantId(), "runtime-default-v1"));
        oldConfig.put("prompt", "person@example.com");
        store.create(
                actor.tenantId(),
                RuntimeConfigService.KIND,
                "legacy-prompt",
                oldConfig,
                "legacy",
                "TEST");
        assertThrows(
                RuntimeBoundaryException.class,
                () -> configs.resolveForEvaluation(actor.tenantId(), "legacy-prompt"));
        assertThrows(
                RuntimeBoundaryException.class,
                () -> RuntimeBinding.bind(order(), null, oldConfig, false));
        assertNull(RuntimeBinding.current());
        assertTrue(calledModels.isEmpty());
    }

    @Test
    void analysisPolicyReferenceCannotBypassPersonalDataCheckThroughRuntimeSnapshot() {
        Map<String, Object> analysis = intervalRule("ANALYSIS-PRIVATE", 0, null);
        analysis.put("ruleType", "analysis");
        analysis.put("content", "联系人：张三");
        var draft =
                policies.create(
                        actor.tenantId(), actor.userId(), Map.of("rules", List.of(analysis)));
        policies.publish(actor.tenantId(), draft.id(), "publisher", draft.version());
        RuntimeBoundaryException failure =
                assertThrows(
                        RuntimeBoundaryException.class,
                        () ->
                                configs.create(
                                        actor.tenantId(),
                                        actor.userId(),
                                        Map.of(
                                                "model",
                                                "test-model",
                                                "prompt",
                                                "safe prompt",
                                                "policy_version",
                                                draft.id())));
        assertEquals("POLICY_CONTAINS_PERSONAL_DATA", failure.getReasonCode());
        Map<String, Object> oldConfig =
                QualityData.copy(configs.resolve(actor.tenantId(), "runtime-default-v1"));
        oldConfig.put("policy_snapshot", List.of(analysis));
        assertEquals(
                "POLICY_CONTAINS_PERSONAL_DATA",
                assertThrows(
                                RuntimeBoundaryException.class,
                                () -> RuntimeBinding.bind(order(), null, oldConfig, false))
                        .getReasonCode());
        assertNull(RuntimeBinding.current());
        assertTrue(calledModels.isEmpty());
    }

    @Test
    void knowledgeDraftAndWithdrawnChunksAreNotSearchableButHistoricalCitationRemains() {
        var draft =
                knowledge.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of("title", "知识", "content", "超时参考材料"));
        assertEquals(
                0, knowledge.search(actor.tenantId(), Map.of("query", "参考材料")).get("total_found"));
        var reviewed =
                knowledge.transition(
                        actor.tenantId(),
                        draft.id(),
                        "reviewer",
                        draft.version(),
                        "review",
                        "reviewed");
        var published =
                knowledge.transition(
                        actor.tenantId(),
                        draft.id(),
                        "publisher",
                        reviewed.version(),
                        "publish",
                        "publish");
        Map<String, Object> found = knowledge.search(actor.tenantId(), Map.of("query", "参考材料"));
        assertEquals(1, found.get("total_found"));
        String citationId =
                PolicyService.ruleMaps(found.get("items")).get(0).get("citation_id").toString();
        Map<String, Object> citation = knowledge.citation(actor.tenantId(), citationId);
        assertEquals("超时参考材料", citation.get("content"));
        assertEquals(0, citation.get("offset_start"));
        assertEquals(QualityData.hash("超时参考材料"), citation.get("content_hash"));
        knowledge.transition(
                actor.tenantId(),
                draft.id(),
                "publisher",
                published.version(),
                "withdraw",
                "obsolete");
        assertEquals(
                0, knowledge.search(actor.tenantId(), Map.of("query", "参考材料")).get("total_found"));
        assertEquals(
                "WITHDRAWN", knowledge.citation(actor.tenantId(), citationId).get("current_state"));
        assertThrows(
                PlatformException.class, () -> knowledge.citation("another-tenant", citationId));
    }

    @Test
    void exactRuleIdAndScopedKnowledgeRespectFilters() {
        assertEquals(
                true,
                PolicyService.ruleMaps(
                                knowledge
                                        .search(actor.tenantId(), Map.of("query", "COMP-001"))
                                        .get("items"))
                        .get(0)
                        .get("exact_id_match"));
        var draft =
                knowledge.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of("title", "知识", "content", "城市隔离文本", "city_codes", List.of("010")));
        var review =
                knowledge.transition(
                        actor.tenantId(), draft.id(), "reviewer", draft.version(), "review", "");
        knowledge.transition(
                actor.tenantId(), draft.id(), "publisher", review.version(), "publish", "");
        assertEquals(
                0,
                knowledge
                        .search(actor.tenantId(), Map.of("query", "城市隔离文本", "city_code", "020"))
                        .get("total_found"));
        assertEquals(
                1,
                knowledge
                        .search(actor.tenantId(), Map.of("query", "城市隔离文本", "city_code", "010"))
                        .get("total_found"));
    }

    @Test
    void candidatePromptAndModelAreActuallyBoundToGatewayAndRestored() {
        var config =
                configs.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of(
                                "model",
                                "model-B",
                                "prompt",
                                "Prompt B",
                                "policy_version",
                                "policy-default-v1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> configs.resolve(actor.tenantId(), config.id()));
        Map<String, Object> resolved = configs.resolveForEvaluation(actor.tenantId(), config.id());
        LlmGateway gateway = new LlmGateway(new ModelRouter(), client);
        try (RuntimeBinding ignored = RuntimeBinding.bind(order(), null, resolved, false)) {
            gateway.chat(
                    LlmChatRequest.builder()
                            .scenario("compensation_suggestion")
                            .messages(
                                    List.of(
                                            LlmMessage.builder()
                                                    .role("system")
                                                    .content("old prompt")
                                                    .build(),
                                            LlmMessage.builder()
                                                    .role("user")
                                                    .content("safe evidence")
                                                    .build()))
                            .build());
            assertEquals("model-B", calledModels.get(0));
            assertTrue(calledPrompts.get(0).contains("Prompt B"));
            assertEquals(
                    QualityData.hash("Prompt B"),
                    RuntimeBinding.callMetadata().get("actual_prompt_hash"));
            assertEquals(order().getOrderId(), RuntimeBinding.evidenceOrder().getOrderId());
            assertEquals(1, RuntimeBinding.policyRules().size());
        }
        assertNull(RuntimeBinding.current());
        assertFalse(RuntimeBinding.deterministicOnly());
    }

    @Test
    void wrongCentAndExtraRulesCauseHardGateFailureAndMissingLabelsRemainUnmeasured() {
        Map<String, Object> sample =
                Map.of(
                        "scenario",
                        "compensation_suggestion",
                        "expected_output",
                        Map.of(
                                "amount",
                                "5.00",
                                "currency",
                                "CNY",
                                "decision_status",
                                "DETERMINED"),
                        "expected_rule_ids",
                        List.of("COMP-001"));
        WorkflowExecution execution =
                WorkflowExecution.builder()
                        .status("SUCCESS")
                        .output(
                                Map.of(
                                        "authoritative_amount",
                                        "5.01",
                                        "currency",
                                        "CNY",
                                        "decision_status",
                                        "DETERMINED",
                                        "matched_rules",
                                        List.of(
                                                Map.of("rule_id", "COMP-001"),
                                                Map.of("rule_id", "WRONG"))))
                        .build();
        Map<String, Object> result = ExactScorer.score(sample, execution);
        assertEquals(false, result.get("hard_gate_passed"));
        Map<String, Object> ruleMetrics =
                QualityData.map(QualityData.map(result.get("measurements")).get("rules"));
        assertEquals(0.5d, ruleMetrics.get("precision"));
        assertEquals(1d, ruleMetrics.get("recall"));
        assertEquals(false, ruleMetrics.get("exact_match"));
        Map<String, Object> unlabelled = ExactScorer.score(Map.of(), execution);
        assertEquals("NOT_MEASURED", unlabelled.get("measurement_status"));
        assertEquals(false, unlabelled.get("hard_gate_passed"));
    }

    @Test
    void nullAuthoritativeAmountDoesNotFallBackToCandidateAndCriticalCitationErrorsFail() {
        Map<String, Object> output =
                new LinkedHashMap<>(
                        Map.of(
                                "suggested_amount",
                                "5.00",
                                "currency",
                                "CNY",
                                "decision_status",
                                "INSUFFICIENT_EVIDENCE"));
        output.put("authoritative_amount", null);
        var execution = WorkflowExecution.builder().status("SUCCESS").output(output).build();
        Map<String, Object> expected = Map.of("expected_output", Map.of("amount", "5.00"));
        assertEquals(false, ExactScorer.score(expected, execution).get("hard_gate_passed"));
        output.put("validation_errors", List.of("evidence_unit_mismatch:e1:amount"));
        var scores =
                ExactScorer.score(Map.of("expected_output", Map.of("currency", "CNY")), execution);
        assertTrue(scores.get("hard_failures").toString().contains("CRITICAL_EVIDENCE_VALIDATION"));
    }

    @Test
    void immutableConfigCanOnlyPublishAgainstExactPassingLabelledReport() {
        var config =
                configs.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of(
                                "model",
                                "fixed-model",
                                "prompt",
                                "candidate",
                                "policy_version",
                                "policy-default-v1",
                                "deterministic_only",
                                true));
        var bad =
                store.create(
                        actor.tenantId(),
                        QualityEvalService.RUN,
                        "bad-run",
                        Map.of("status", "COMPLETED", "hard_gate_passed", false),
                        "SYSTEM",
                        "TEST");
        assertThrows(
                IllegalArgumentException.class,
                () -> configs.publish(actor.tenantId(), config.id(), "publisher", 1, bad));
        var report =
                store.create(
                        actor.tenantId(),
                        QualityEvalService.RUN,
                        "good-run",
                        Map.of(
                                "status",
                                "COMPLETED",
                                "hard_gate_passed",
                                true,
                                "runtime_config_id",
                                config.id(),
                                "resolved_config",
                                config.data()),
                        "SYSTEM",
                        "TEST");
        var published = configs.publish(actor.tenantId(), config.id(), "publisher", 1, report);
        assertEquals(config.data().get("config_hash"), published.data().get("config_hash"));
        assertEquals("PUBLISHED", configs.resolve(actor.tenantId(), config.id()).get("state"));
    }

    @Test
    void asyncEvaluationFreezesEvidenceAndReplayNeverCallsModel() throws Exception {
        WorkflowEngine engine = new WorkflowEngine();
        AtomicInteger modelCalls = new AtomicInteger();
        LlmGateway gateway =
                new LlmGateway(
                        new ModelRouter(),
                        (model, messages, tools, temp, tokens) -> {
                            modelCalls.incrementAndGet();
                            assertEquals("system", messages.get(0).getRole());
                            assertNotEquals("Prompt B", messages.get(0).getContent());
                            assertTrue(
                                    messages.stream()
                                            .anyMatch(
                                                    message ->
                                                            "user".equals(message.getRole())
                                                                    && message.getContent()
                                                                            .contains("Prompt B")));
                            return "{}";
                        });
        engine.registerHandler(
                "compensation_suggestion",
                (input, execution) -> {
                    assertEquals(
                            order().getDeliveryTime(),
                            RuntimeBinding.evidenceOrder().getDeliveryTime());
                    if (!RuntimeBinding.deterministicOnly()) {
                        gateway.chat(
                                LlmChatRequest.builder()
                                        .messages(
                                                List.of(
                                                        LlmMessage.builder()
                                                                .role("user")
                                                                .content("contract")
                                                                .build()))
                                        .build());
                    }
                    return Map.of(
                            "authoritative_amount",
                            "5.00",
                            "currency",
                            "CNY",
                            "decision_status",
                            "DETERMINED");
                });
        QualityEvalService evaluator =
                new QualityEvalService(
                        store,
                        configs,
                        engine,
                        new OrderSource("demo", "", "", ""),
                        new EvalCaseManager(),
                        "test-sha");
        try {
            var candidate =
                    configs.create(
                            actor.tenantId(),
                            actor.userId(),
                            Map.of(
                                    "model",
                                    "model-B",
                                    "prompt",
                                    "Prompt B",
                                    "policy_version",
                                    "policy-default-v1"));
            var dataset =
                    evaluator.createDataset(
                            actor,
                            Map.of("split", "BOUNDARY_REGRESSION", "samples", List.of(sample())));
            var run =
                    evaluator.start(
                            actor,
                            Map.of(
                                    "dataset_version",
                                    dataset.id(),
                                    "runtime_config_id",
                                    candidate.id()));
            await(evaluator, run.id());
            var finished = evaluator.require(actor, run.id());
            assertEquals("COMPLETED", finished.data().get("status"));
            assertEquals(true, finished.data().get("hard_gate_passed"));
            assertEquals(1, modelCalls.get());
            var replay = evaluator.replay(actor, run.id());
            assertEquals("STORED_OUTPUT_RESCORING", replay.data().get("replay_mode"));
            assertEquals(
                    true, evaluator.results(actor, replay.id()).get(0).data().get("replay_equal"));
            assertEquals(1, modelCalls.get());
            assertEquals(
                    candidate.id(),
                    QualityData.map(finished.data().get("resolved_config")).get("config_id"));
            assertEquals(
                    QualityData.hash(sample().get("evidence_order")),
                    QualityData.hash(
                            QualityData.map(finished.data().get("dataset_snapshot")).get("samples")
                                            instanceof List<?> l
                                    ? QualityData.map(l.get(0)).get("evidence_order")
                                    : null));
        } finally {
            evaluator.shutdown();
        }
    }

    @Test
    void blindDatasetCannotBeCopiedIntoTuningAfterPublication() {
        QualityEvalService evaluator =
                new QualityEvalService(
                        store,
                        configs,
                        new WorkflowEngine(),
                        new OrderSource("demo", "", "", ""),
                        new EvalCaseManager(),
                        "test-sha");
        try {
            evaluator.createDataset(
                    actor, Map.of("split", "FROZEN_BLIND", "samples", List.of(sample())));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            evaluator.createDataset(
                                    actor,
                                    Map.of(
                                            "split",
                                            "REAL_LABELLED",
                                            "samples",
                                            List.of(sample()))));
            Map<String, Object> renamed = QualityData.copy(sample());
            Map<String, Object> evidence =
                    QualityData.copy(QualityData.map(renamed.get("evidence_order")));
            evidence.put("orderId", "renamed-order");
            renamed.put("evidence_order", evidence);
            renamed.put("input", Map.of("order_id", "renamed-order"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            evaluator.createDataset(
                                    actor,
                                    Map.of("split", "REAL_LABELLED", "samples", List.of(renamed))));
        } finally {
            evaluator.shutdown();
        }
    }

    @Test
    void startupMarksInterruptedDurableRunAndKeepsFrozenSnapshots() {
        QualityEvalService evaluator =
                new QualityEvalService(
                        store,
                        configs,
                        new WorkflowEngine(),
                        new OrderSource("demo", "", "", ""),
                        new EvalCaseManager(),
                        "test-sha");
        try {
            store.create(
                    actor.tenantId(),
                    QualityEvalService.RUN,
                    "interrupted",
                    Map.of(
                            "status",
                            "RUNNING",
                            "dataset_snapshot",
                            Map.of("samples", List.of(sample())),
                            "resolved_config",
                            configs.resolveForEvaluation(actor.tenantId(), "runtime-default-v1")),
                    "author",
                    "TEST");
            evaluator.recoverInterruptedRuns();
            var recovered = evaluator.require(actor, "interrupted");
            assertEquals("FAILED", recovered.data().get("status"));
            assertEquals("PROCESS_RESTARTED", recovered.data().get("error_code"));
            assertTrue(recovered.data().containsKey("dataset_snapshot"));
        } finally {
            evaluator.shutdown();
        }
    }

    @Test
    void feedbackEnforcesTenantDecisionScopeIdentityAndIndependentVerification() {
        var feedback = new StructuredFeedbackService(store);
        store.create(
                actor.tenantId(),
                "case",
                "case-one",
                Map.of("city_code", "010", "station_id", "S001"),
                "author",
                "TEST");
        store.create(
                actor.tenantId(),
                "decision",
                "decision-one",
                Map.of(
                        "case_id",
                        "case-one",
                        "amount",
                        "5.00",
                        "currency",
                        "CNY",
                        "evidence_snapshot_id",
                        "evidence-one",
                        "execution_id",
                        "execution-one",
                        "policy_version",
                        "policy-default-v1",
                        "runtime_config_id",
                        "runtime-default-v1"),
                "author",
                "TEST");
        Map<String, Object> request =
                Map.of(
                        "case_id",
                        "case-one",
                        "decision_id",
                        "decision-one",
                        "decision_version",
                        1,
                        "category",
                        "AMOUNT_ERROR",
                        "submitted_by",
                        "forged-identity",
                        "correction",
                        Map.of(
                                "field_path",
                                "amount",
                                "original_value",
                                "5.00",
                                "suggested_value",
                                "1.00",
                                "reason_code",
                                "INCORRECT_BOUNDARY",
                                "evidence_refs",
                                List.of("evidence-one")));
        var entry = feedback.create(actor, request);
        assertEquals(actor.userId(), entry.data().get("submitted_by"));
        assertEquals(
                403,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        feedback.transition(
                                                actor,
                                                entry.id(),
                                                entry.version(),
                                                Map.of(
                                                        "status",
                                                        "CONFIRMED",
                                                        "reason",
                                                        "self confirmation")))
                        .status());
        var verifier =
                new AuthContext.Principal(
                        actor.tenantId(), "verifier", Set.of("QUALITY"), Set.of("*"), Set.of("*"));
        var verified =
                feedback.transition(
                        verifier,
                        entry.id(),
                        entry.version(),
                        Map.of("status", "CONFIRMED", "reason", "verified external facts"));
        assertEquals("CONFIRMED", verified.data().get("status"));
        var scopedVerifier =
                new AuthContext.Principal(
                        actor.tenantId(),
                        "scoped-verifier",
                        Set.of("QUALITY"),
                        Set.of("010"),
                        Set.of("S001"));
        var linked =
                feedback.transition(
                        scopedVerifier,
                        entry.id(),
                        verified.version(),
                        Map.of(
                                "status",
                                "LINKED_FIX",
                                "reason",
                                "fixed policy",
                                "issue_ref",
                                "TEST-1"));
        store.create(
                actor.tenantId(),
                "eval_run",
                "restricted-regression",
                Map.of(
                        "status",
                        "COMPLETED",
                        "hard_gate_passed",
                        true,
                        "resolved_config",
                        Map.of(
                                "policy_scope",
                                Map.of("city_codes", List.of(), "station_ids", List.of()),
                                "knowledge_snapshot",
                                List.of(
                                        Map.of(
                                                "city_codes",
                                                List.of("010"),
                                                "station_ids",
                                                List.of("S002")))),
                        "dataset_snapshot",
                        Map.of(
                                "samples",
                                List.of(
                                        Map.of(
                                                "source_feedback_id",
                                                entry.id(),
                                                "evidence_order",
                                                Map.of("cityCode", "010", "stationId", "S001"))))),
                "verifier",
                "TEST");
        var regression =
                Map.<String, Object>of(
                        "status",
                        "REGRESSION_VERIFIED",
                        "reason",
                        "verified regression",
                        "eval_run_id",
                        "restricted-regression");
        assertEquals(
                404,
                assertThrows(
                                PlatformException.class,
                                () ->
                                        feedback.transition(
                                                scopedVerifier,
                                                entry.id(),
                                                linked.version(),
                                                regression))
                        .status());
        assertEquals(
                "LINKED_FIX", feedback.require(scopedVerifier, entry.id()).data().get("status"));
        assertEquals(
                "REGRESSION_VERIFIED",
                feedback.transition(verifier, entry.id(), linked.version(), regression)
                        .data()
                        .get("status"));
        var wrongTenant =
                new AuthContext.Principal(
                        "different-tenant", "other", Set.of("QUALITY"), Set.of("*"), Set.of("*"));
        assertEquals(
                404,
                assertThrows(PlatformException.class, () -> feedback.create(wrongTenant, request))
                        .status());
        var wrongScope =
                new AuthContext.Principal(
                        actor.tenantId(),
                        "other",
                        Set.of("QUALITY"),
                        Set.of("020"),
                        Set.of("S002"));
        assertEquals(
                404,
                assertThrows(PlatformException.class, () -> feedback.create(wrongScope, request))
                        .status());
        assertEquals(entry.id(), feedback.require(verifier, entry.id()).id());
        assertEquals(
                404,
                assertThrows(
                                PlatformException.class,
                                () -> feedback.require(wrongScope, entry.id()))
                        .status());
        assertEquals(
                404,
                assertThrows(
                                PlatformException.class,
                                () -> feedback.require(wrongTenant, entry.id()))
                        .status());
        Map<String, Object> stale = QualityData.copy(request);
        stale.put("decision_version", 9);
        assertEquals(
                409,
                assertThrows(PlatformException.class, () -> feedback.create(actor, stale))
                        .status());
        Map<String, Object> forgedValue = QualityData.copy(request);
        forgedValue.put(
                "correction",
                Map.of(
                        "field_path",
                        "amount",
                        "original_value",
                        "99.00",
                        "suggested_value",
                        "1.00",
                        "reason_code",
                        "wrong"));
        assertEquals(
                409,
                assertThrows(PlatformException.class, () -> feedback.create(actor, forgedValue))
                        .status());
    }

    @Test
    void tokenUsageUnknownDoesNotMeanZeroAndVersionedPricesComputeKnownCost() {
        var config =
                configs.create(
                        actor.tenantId(),
                        actor.userId(),
                        Map.of(
                                "model",
                                "test-model",
                                "prompt",
                                "prompt",
                                "policy_version",
                                "policy-default-v1",
                                "pricing",
                                Map.of(
                                        "version",
                                        "prices-2026",
                                        "currency",
                                        "CNY",
                                        "input_per_million",
                                        "10",
                                        "output_per_million",
                                        "20")));
        try (RuntimeBinding ignored =
                RuntimeBinding.bind(
                        order(),
                        null,
                        configs.resolveForEvaluation(actor.tenantId(), config.id()),
                        false)) {
            assertEquals("UNKNOWN", RuntimeBinding.callMetadata().get("usage_status"));
            assertEquals("UNKNOWN", RuntimeBinding.callMetadata().get("cost_status"));
            assertFalse(RuntimeBinding.callMetadata().containsKey("estimated_cost"));
            RuntimeBinding.recordCall(
                    Map.of(
                            "usage_status",
                            "KNOWN",
                            "prompt_tokens",
                            1000,
                            "completion_tokens",
                            200));
            assertEquals("KNOWN", RuntimeBinding.callMetadata().get("cost_status"));
            assertEquals("0.014", RuntimeBinding.callMetadata().get("estimated_cost"));
            assertEquals("prices-2026", RuntimeBinding.callMetadata().get("price_version"));
        }
    }

    @Test
    void publishedRuntimeRoutesUseStableCaseBucketAndPersistExactSelectedConfig() {
        var route =
                configs.createRoute(
                        actor.tenantId(),
                        "publisher",
                        Map.of(
                                "scenario",
                                "compensation_suggestion",
                                "primary_config_id",
                                "runtime-default-v1",
                                "fallback_config_id",
                                "runtime-default-v1",
                                "rollout_percent",
                                50,
                                "city_codes",
                                List.of("010"),
                                "station_ids",
                                List.of("S001")));
        var first =
                configs.route(
                        actor.tenantId(), "compensation_suggestion", "010", "S001", "case-stable");
        var second =
                configs.route(
                        actor.tenantId(), "compensation_suggestion", "010", "S001", "case-stable");
        assertEquals(route.id(), first.get("route_id"));
        assertEquals(first.get("rollout_bucket"), second.get("rollout_bucket"));
        assertEquals(first.get("config_id"), second.get("config_id"));
        assertEquals(first.get("config_hash"), second.get("config_hash"));
        assertFalse(
                configs.route(
                                actor.tenantId(),
                                "compensation_suggestion",
                                "020",
                                "S002",
                                "case-stable")
                        .containsKey("route_id"));
    }

    private Map<String, Object> sample() {
        return Map.of(
                "sample_id",
                "boundary-1",
                "scenario",
                "compensation_suggestion",
                "input",
                Map.of("order_id", "DEMO-001"),
                "evidence_order",
                JsonUtil.toMap(order()),
                "expected_output",
                Map.of("amount", "5.00", "currency", "CNY", "decision_status", "DETERMINED"));
    }

    private Map<String, Object> intervalRule(String id, int min, Integer max) {
        Map<String, Object> rule = JsonUtil.toMap(ruleBase.findById("COMP-001").orElseThrow());
        rule.put("ruleId", id);
        Map<String, Object> conditions =
                new LinkedHashMap<>(
                        Map.of("complaint_type", "OVERTIME", "min_overtime_minutes", min));
        if (max != null) {
            conditions.put("max_overtime_minutes", max);
        }
        rule.put("conditions", conditions);
        return rule;
    }

    private OrderInfo order() {
        return OrderFixtures.build("DEMO-001", LocalDateTime.of(2026, 1, 1, 12, 0));
    }

    private void await(QualityEvalService service, String id) throws InterruptedException {
        long until = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < until) {
            String status = service.require(actor, id).data().get("status").toString();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Evaluation did not complete within 5 seconds");
    }
}
