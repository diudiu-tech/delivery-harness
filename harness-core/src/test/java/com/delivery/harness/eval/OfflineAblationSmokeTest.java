package com.delivery.harness.eval;

import com.delivery.harness.application.CaseApplicationService;
import com.delivery.harness.application.UnstructuredExtractionPort;
import com.delivery.harness.cases.DeliveryCase;
import com.delivery.harness.cases.InMemoryCaseStore;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.diagnosis.DeterministicResidualEngine;
import com.delivery.harness.evidence.ConversationMessage;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.evidence.SpeakerRole;
import com.delivery.harness.evidence.SyntheticEvidenceSource;
import com.delivery.harness.extraction.ClaimVerifier;
import com.delivery.harness.extraction.ClaimedFact;
import com.delivery.harness.extraction.RegexSignalBaseline;
import com.delivery.harness.extraction.UnstructuredExtractor;
import com.delivery.harness.llm.gateway.LlmClient;
import com.delivery.harness.llm.gateway.LlmGateway;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.delivery.harness.llm.router.ModelRouter;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import com.delivery.harness.policy.PolicyBundleLoader;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import com.delivery.harness.template.DeterministicCustomerTemplateRenderer;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline smoke verification of the contract fixtures ({@code labels.json}),
 * the 20-line {@link RegexSignalBaseline} on {@code text-corpus.json}, and
 * independent hard gates. Uses a deterministic stub for LLM transport and
 * explicitly marks the emitted Markdown report as {@code PIPELINE_SMOKE_ONLY}.
 */
class OfflineAblationSmokeTest {

    private static final Instant T0 = Instant.parse("2026-09-01T04:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void verifiesAllContractFixturesAndGeneratesSmokeAblationMarkdownReport() throws IOException {
        PolicyBundleLoader policyLoader = PolicyBundleLoader.loadDefault();
        SyntheticEvidenceSource evidenceSource = new SyntheticEvidenceSource(policyLoader);
        ClaimVerifier verifier = new ClaimVerifier();
        RegexSignalBaseline regexBaseline = new RegexSignalBaseline(verifier);

        // Gate 6: Unknown order must return empty (never fabricated).
        assertEquals(Optional.empty(), evidenceSource.collect("ORD-EVAL-UNKNOWN"));

        // Run all contract fixtures from isolated test labels.json.
        Map<String, Object> labelsDoc = readJson("eval/labels.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) labelsDoc.get("fixtures");

        UnstructuredExtractor disabledExtractor = buildExtractor(verifier, (m, msgs, t, temp, max) -> "{}", false);
        UnstructuredExtractor stubExtractor = buildExtractor(verifier, stubLlmResponder(), true);

        int hardGateFailures = 0;
        for (Map<String, Object> fixture : fixtures) {
            String orderId = String.valueOf(fixture.get("orderId"));
            String complaintType = String.valueOf(fixture.get("complaintType"));
            String attestedDamage = fixture.get("attestedDamageLevel") == null
                    ? null : String.valueOf(fixture.get("attestedDamageLevel"));

            DeliveryCase detCase = runCase(policyLoader, evidenceSource, disabledExtractor,
                    orderId, complaintType, attestedDamage);
            DeliveryCase extCase = runCase(policyLoader, evidenceSource, stubExtractor,
                    orderId, complaintType, attestedDamage);

            assertEquals(String.valueOf(fixture.get("expectedAdjudication")),
                    detCase.latestRun().adjudication().status().name(),
                    "Adjudication mismatch on " + orderId + "/" + complaintType);

            List<String> gateViolations = IndependentEvalScorer.checkContractHardGates(detCase, extCase, false);
            hardGateFailures += gateViolations.size();
            assertTrue(gateViolations.isEmpty(),
                    "Hard-gate violations on " + orderId + ": " + gateViolations);
        }

        // Run REGEX_BASELINE and Stub-LLM on dev & holdout text corpus.
        Map<String, Object> corpusDoc = readJson("eval/text-corpus.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textCases = (List<Map<String, Object>>) corpusDoc.get("cases");

        List<IndependentEvalScorer.TextCaseOutcome> regexDev = new ArrayList<>();
        List<IndependentEvalScorer.TextCaseOutcome> regexHold = new ArrayList<>();
        List<IndependentEvalScorer.TextCaseOutcome> stubDev = new ArrayList<>();
        List<IndependentEvalScorer.TextCaseOutcome> stubHold = new ArrayList<>();

        for (Map<String, Object> tc : textCases) {
            String caseId = String.valueOf(tc.get("caseId"));
            String split = String.valueOf(tc.get("split"));
            @SuppressWarnings("unchecked")
            List<String> expectedSignals = (List<String>) tc.get("expectedSignals");
            boolean expectedUntrusted = Boolean.TRUE.equals(tc.get("expectedUntrusted"));
            EvidenceSnapshot snap = snapshotFromTextCase(caseId, tc);

            RegexSignalBaseline.BaselineResult regexRes = regexBaseline.extract(snap);
            List<String> regexSignals = regexRes.signals().stream().map(f -> f.signalType().name()).toList();
            var regexOutcome = new IndependentEvalScorer.TextCaseOutcome(
                    caseId, split, expectedSignals, regexSignals,
                    expectedUntrusted, regexRes.untrustedTextDetected());

            UnstructuredExtractionPort.ExtractionOutcome stubRes = stubExtractor.extractWithLlm(snap);
            List<String> stubSignals = stubRes.unconfirmedSignals().stream()
                    .map(m -> String.valueOf(m.get("signal_type"))).toList();
            var stubOutcome = new IndependentEvalScorer.TextCaseOutcome(
                    caseId, split, expectedSignals, stubSignals,
                    expectedUntrusted, stubRes.untrustedTextDetected());

            if ("dev".equals(split)) {
                regexDev.add(regexOutcome);
                stubDev.add(stubOutcome);
            } else {
                regexHold.add(regexOutcome);
                stubHold.add(stubOutcome);
            }
        }

        IndependentEvalScorer.TextSplitMetrics regexDevMetrics = IndependentEvalScorer.scoreTextSplit(regexDev);
        IndependentEvalScorer.TextSplitMetrics regexHoldMetrics = IndependentEvalScorer.scoreTextSplit(regexHold);
        IndependentEvalScorer.TextSplitMetrics stubDevMetrics = IndependentEvalScorer.scoreTextSplit(stubDev);
        IndependentEvalScorer.TextSplitMetrics stubHoldMetrics = IndependentEvalScorer.scoreTextSplit(stubHold);

        assertEquals(0, regexDevMetrics.exemptionFalsePositives(), "Regex baseline must have 0 exemption FPs on dev");
        assertEquals(0, regexHoldMetrics.exemptionFalsePositives(), "Regex baseline must have 0 exemption FPs on holdout");
        assertTrue(regexHoldMetrics.hardGatePassed(), "Regex baseline hard gate failed on holdout");
        assertTrue(stubHoldMetrics.hardGatePassed(), "Stub smoke hard gate failed on holdout");

        String report = AblationMarkdownReportGenerator.render(
                "stub-smoke-client", UnstructuredExtractor.promptHash(), true,
                fixtures.size(), hardGateFailures,
                regexDevMetrics, regexHoldMetrics, stubDevMetrics, stubHoldMetrics);

        Path reportPath = Path.of("target/ablation-smoke-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);

        assertTrue(report.contains("PIPELINE_SMOKE_ONLY"));
        assertTrue(report.contains(UnstructuredExtractor.promptHash()));
    }

    private static DeliveryCase runCase(
            PolicyBundleLoader loader,
            SyntheticEvidenceSource source,
            UnstructuredExtractionPort extractionPort,
            String orderId,
            String complaintType,
            String attestedDamage) {
        CaseApplicationService svc = new CaseApplicationService(
                source, new InMemoryCaseStore(50), loader,
                new SingleSourcePolicyEngine(loader), new DeterministicResidualEngine(loader),
                new DeterministicCustomerTemplateRenderer(), new TraceService(50),
                new MetricsService(), Clock.fixed(T0, SHANGHAI), extractionPort);
        DeliveryCase created = svc.createCase(
                new CaseApplicationService.CreateCaseCommand(orderId, complaintType, null)).deliveryCase();
        if (attestedDamage != null) {
            return svc.attestEvidence(created.caseId(),
                    new CaseApplicationService.AttestCommand("DAMAGE_LEVEL", attestedDamage, "eval-op", created.version()));
        }
        return created;
    }

    private static UnstructuredExtractor buildExtractor(
            ClaimVerifier verifier, LlmClient client, boolean enabled) {
        ModelRouter router = new ModelRouter();
        router.registerModel(UnstructuredExtractor.SCENARIO_EXTRACTION, "stub-smoke-client");
        return new UnstructuredExtractor(
                new LlmGateway(router, client), new StructuredOutputParser(), verifier, enabled);
    }

    /** Minimal deterministic stub used strictly for offline smoke checks. */
    private static LlmClient stubLlmResponder() {
        return (model, messages, tools, temperature, maxTokens) -> {
            String user = messages.get(messages.size() - 1).getContent();
            if (user.contains("麻烦直接送到对面B座前台")) {
                return "{\"signals\":[{\"signal_type\":\"CUSTOMER_CHANGED_ADDRESS\","
                        + "\"source_evidence_id\":\"EV-IM-01\",\"quote\":\"麻烦直接送到对面B座前台\"}]}";
            }
            return "{\"signals\":[]}";
        };
    }

    @SuppressWarnings("unchecked")
    static EvidenceSnapshot snapshotFromTextCase(String caseId, Map<String, Object> tc) {
        List<Map<String, Object>> rawMsgs = (List<Map<String, Object>>) tc.get("messages");
        List<ConversationMessage> messages = new ArrayList<>();
        for (Map<String, Object> m : rawMsgs) {
            messages.add(new ConversationMessage(
                    String.valueOf(m.get("evidenceId")),
                    SpeakerRole.valueOf(String.valueOf(m.get("speakerRole"))),
                    T0,
                    String.valueOf(m.get("text"))));
        }
        return EvidenceSnapshot.create(caseId, SHANGHAI, T0, T0, "SYNTHETIC", List.of(), messages);
    }

    static Map<String, Object> readJson(String classpath) throws IOException {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return JsonUtil.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
        }
    }
}
