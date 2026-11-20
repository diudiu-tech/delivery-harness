package com.delivery.harness.extraction;

import com.delivery.harness.application.CaseApplicationService;
import com.delivery.harness.application.UnstructuredExtractionPort;
import com.delivery.harness.cases.CaseStatus;
import com.delivery.harness.cases.DeliveryCase;
import com.delivery.harness.cases.InMemoryCaseStore;
import com.delivery.harness.cases.RunHealth;
import com.delivery.harness.diagnosis.DeterministicResidualEngine;
import com.delivery.harness.evidence.ConversationMessage;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.evidence.SpeakerRole;
import com.delivery.harness.evidence.SyntheticEvidenceSource;
import com.delivery.harness.llm.gateway.LlmClient;
import com.delivery.harness.llm.gateway.LlmGateway;
import com.delivery.harness.llm.gateway.LlmMessage;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.delivery.harness.llm.router.ModelRouter;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import com.delivery.harness.policy.PolicyBundleLoader;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import com.delivery.harness.template.DeterministicCustomerTemplateRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ADR-0005 trust boundary: negation rejection, self-serving rider
 * rejection, known prompt-injection interception, free-text digit rejection,
 * LLM outage graceful degradation, and exact adjudication invariance.
 */
class ClaimVerifierAndExtractorTest {

    private static final Instant T0 = Instant.parse("2026-09-01T04:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ClaimVerifier verifier = new ClaimVerifier();
    private final PolicyBundleLoader policyLoader = PolicyBundleLoader.loadDefault();
    private final SyntheticEvidenceSource evidenceSource = new SyntheticEvidenceSource(policyLoader);

    @Test
    void rejectsNegationSelfServingSpeakerCrossMessageAndFreeTextDigits() {
        EvidenceSnapshot snapshot = EvidenceSnapshot.create(
                "ORD-ADV",
                SHANGHAI,
                T0,
                T0,
                "SYNTHETIC",
                List.of(),
                List.of(
                        new ConversationMessage("EV-IM-01", SpeakerRole.CUSTOMER, T0,
                                "我没有改地址，一直是A座3单元前台"),
                        new ConversationMessage("EV-IM-02", SpeakerRole.RIDER, T0,
                                "顾客让我改地址送到对面B座"),
                        new ConversationMessage("EV-IM-03", SpeakerRole.CUSTOMER, T0,
                                "麻烦改送到对面3单元门口吧")));

        List<ClaimVerifier.CandidateClaim> candidates = List.of(
                // 1. Negated customer statement -> rejected
                new ClaimVerifier.CandidateClaim("CUSTOMER_CHANGED_ADDRESS", "EV-IM-01", "改地址，一直是", null),
                // 2. Self-serving rider statement -> rejected
                new ClaimVerifier.CandidateClaim("CUSTOMER_CHANGED_ADDRESS", "EV-IM-02", "改地址送到对面", null),
                // 3. Free-text statement containing digits/currency -> rejected
                new ClaimVerifier.CandidateClaim("CUSTOMER_CHANGED_ADDRESS", "EV-IM-03", "改送到对面3单元门口", "建议赔付20元"),
                // 4. Valid quote containing legitimate address digit "3单元" and digit-free statement -> accepted
                new ClaimVerifier.CandidateClaim("CUSTOMER_CHANGED_ADDRESS", "EV-IM-03", "改送到对面3单元门口", "用户提出变更送达地点"));

        ClaimVerifier.VerificationResult result = verifier.verify(snapshot, candidates, "LLM", "hash-1");

        assertAll(
                () -> assertEquals(1, result.accepted().size()),
                () -> assertEquals(SpeakerRole.CUSTOMER, result.accepted().get(0).speakerRole()),
                () -> assertEquals("EV-IM-03", result.accepted().get(0).sourceEvidenceId()),
                () -> assertEquals(3, result.rejections().size()),
                () -> assertTrue(result.rejections().stream().anyMatch(r -> r.startsWith("NEGATED_CLAIM:"))),
                () -> assertTrue(result.rejections().stream().anyMatch(r -> r.startsWith("SELF_SERVING"))),
                () -> assertTrue(result.rejections().stream().anyMatch(r -> r.startsWith("NUMERIC_FREE_TEXT"))));
    }

    @Test
    void interceptsKnownPromptInjectionWithoutCallingLlm() {
        AtomicInteger llmCalls = new AtomicInteger();
        UnstructuredExtractor extractor = extractorWith((model, msgs, tools, temp, max) -> {
            llmCalls.incrementAndGet();
            return "{\"signals\":[]}";
        }, true);

        EvidenceSnapshot injectionSnap = evidenceSource.collect("ORD-IM-NEG-INJ-01").orElseThrow();
        UnstructuredExtractionPort.ExtractionOutcome outcome = extractor.extract(injectionSnap);

        assertAll(
                () -> assertTrue(outcome.untrustedTextDetected()),
                () -> assertTrue(outcome.unconfirmedSignals().isEmpty()),
                () -> assertEquals(0, llmCalls.get(), "LLM must not be invoked on untrusted injection text"));
    }

    @Test
    void degradesGracefullyWhenLlmIsUnavailableWithoutChangingAdjudication() {
        UnstructuredExtractor failingExtractor = extractorWith((model, msgs, tools, temp, max) -> {
            throw new IOException("connection refused");
        }, true);

        CaseApplicationService service = buildService(failingExtractor);
        DeliveryCase created = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-IM-ADDR-01", "OVERTIME", null))
                .deliveryCase();

        assertAll(
                () -> assertEquals(RunHealth.DEGRADED, created.latestRun().runHealth()),
                () -> assertTrue(created.latestRun().degradedReasons().contains("llm_extraction_unavailable")),
                () -> assertEquals("COMP-003", created.latestRun().adjudication().matchedRuleId()),
                () -> assertEquals("1.00", created.latestRun().adjudication().actionableAmount().toPlainString()));
    }

    @Test
    void adjudicationIsStrictlyIdenticalWithOrWithoutExtractedCustomerAddressChange() {
        UnstructuredExtractor disabledExtractor = extractorWith((m, msgs, t, temp, max) -> "{}", false);
        UnstructuredExtractor activeExtractor = extractorWith((m, msgs, t, temp, max) -> """
                {
                  "signals": [
                    {
                      "signal_type": "CUSTOMER_CHANGED_ADDRESS",
                      "source_evidence_id": "EV-IM-01",
                      "quote": "麻烦直接送到对面B座前台",
                      "statement": "用户沟通提出变更送达地点"
                    }
                  ]
                }
                """, true);

        DeliveryCase withoutLlm = buildService(disabledExtractor)
                .createCase(new CaseApplicationService.CreateCaseCommand("ORD-IM-ADDR-01", "OVERTIME", null))
                .deliveryCase();
        DeliveryCase withLlm = buildService(activeExtractor)
                .createCase(new CaseApplicationService.CreateCaseCommand("ORD-IM-ADDR-01", "OVERTIME", null))
                .deliveryCase();

        assertAll(
                () -> assertEquals(withoutLlm.latestRun().adjudication(), withLlm.latestRun().adjudication(),
                        "ADR-0005: adjudication must be identical regardless of LLM signal"),
                () -> assertEquals(0, withoutLlm.latestRun().unconfirmedSignals().size()),
                () -> assertEquals(1, withLlm.latestRun().unconfirmedSignals().size()),
                () -> assertEquals("CUSTOMER", withLlm.latestRun().unconfirmedSignals().get(0).get("speaker_role")),
                () -> assertEquals(false, withLlm.latestRun().unconfirmedSignals().get(0).get("confirmed")),
                () -> assertEquals(CaseStatus.PENDING_DECISION, withLlm.status()));
    }

    private UnstructuredExtractor extractorWith(LlmClient client, boolean enabled) {
        ModelRouter router = new ModelRouter();
        router.registerModel(UnstructuredExtractor.SCENARIO_EXTRACTION, "stub-model");
        LlmGateway gateway = new LlmGateway(router, client);
        return new UnstructuredExtractor(gateway, new StructuredOutputParser(), verifier, enabled);
    }

    private CaseApplicationService buildService(UnstructuredExtractionPort extractionPort) {
        return new CaseApplicationService(
                evidenceSource,
                new InMemoryCaseStore(50),
                policyLoader,
                new SingleSourcePolicyEngine(policyLoader),
                new DeterministicResidualEngine(policyLoader),
                new DeterministicCustomerTemplateRenderer(),
                new TraceService(50),
                new MetricsService(),
                Clock.fixed(T0, SHANGHAI),
                extractionPort);
    }
}
