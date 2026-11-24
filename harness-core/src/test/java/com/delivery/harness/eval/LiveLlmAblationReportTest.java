package com.delivery.harness.eval;

import com.delivery.harness.application.CaseApplicationService;
import com.delivery.harness.application.UnstructuredExtractionPort;
import com.delivery.harness.cases.DeliveryCase;
import com.delivery.harness.cases.InMemoryCaseStore;
import com.delivery.harness.diagnosis.DeterministicResidualEngine;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.evidence.SyntheticEvidenceSource;
import com.delivery.harness.extraction.ClaimVerifier;
import com.delivery.harness.extraction.RegexSignalBaseline;
import com.delivery.harness.extraction.UnstructuredExtractor;
import com.delivery.harness.llm.gateway.HttpLlmClient;
import com.delivery.harness.llm.gateway.LlmGateway;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.delivery.harness.llm.router.ModelRouter;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import com.delivery.harness.policy.PolicyBundleLoader;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import com.delivery.harness.template.DeterministicCustomerTemplateRenderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live-model CLI/Maven ablation runner excluded from default offline CI via
 * {@code @Tag("live-llm")}.
 *
 * <p>Run against a local Ollama or OpenAI-compatible endpoint with:
 * <pre>
 *   ./mvnw -pl harness-core test -Dgroups=live-llm -Dtest=LiveLlmAblationReportTest
 * </pre>
 */
@Tag("live-llm")
class LiveLlmAblationReportTest {

    private static final Instant T0 = Instant.parse("2026-09-01T04:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void runsLiveModelAblationAndWritesMarkdownReport() throws IOException {
        String endpoint = System.getenv().getOrDefault("HARNESS_LLM_ENDPOINT", "http://localhost:11434");
        String model = System.getenv().getOrDefault("HARNESS_LLM_MODEL", "qwen2.5:7b");
        String apiKey = System.getenv().getOrDefault("HARNESS_LLM_API_KEY", "");

        HttpLlmClient httpClient = new HttpLlmClient();
        ReflectionTestUtils.setField(httpClient, "defaultEndpoint", endpoint);
        ReflectionTestUtils.setField(httpClient, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(httpClient, "apiKey", apiKey);

        PolicyBundleLoader policyLoader = PolicyBundleLoader.loadDefault();
        SyntheticEvidenceSource evidenceSource = new SyntheticEvidenceSource(policyLoader);
        ModelRouter router = new ModelRouter();
        router.registerModel(UnstructuredExtractor.SCENARIO_EXTRACTION, model);
        ClaimVerifier verifier = new ClaimVerifier();
        RegexSignalBaseline regexBaseline = new RegexSignalBaseline(verifier);
        UnstructuredExtractor disabledExtractor = new UnstructuredExtractor(
                new LlmGateway(router, (m, msgs, t, temp, max) -> "{}"),
                new StructuredOutputParser(), verifier, false);
        UnstructuredExtractor llmExtractor = new UnstructuredExtractor(
                new LlmGateway(router, httpClient), new StructuredOutputParser(), verifier, true);

        // 1. Run all contract fixtures from isolated labels.json against real IndependentEvalScorer gates.
        Map<String, Object> labelsDoc = OfflineAblationSmokeTest.readJson("eval/labels.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) labelsDoc.get("fixtures");
        int hardGateFailures = 0;
        for (Map<String, Object> fixture : fixtures) {
            String orderId = String.valueOf(fixture.get("orderId"));
            String complaintType = String.valueOf(fixture.get("complaintType"));
            String attestedDamage = fixture.get("attestedDamageLevel") == null
                    ? null : String.valueOf(fixture.get("attestedDamageLevel"));

            DeliveryCase detCase = runLiveFixtureCase(
                    policyLoader, evidenceSource, disabledExtractor, orderId, complaintType, attestedDamage);
            DeliveryCase extCase = runLiveFixtureCase(
                    policyLoader, evidenceSource, llmExtractor, orderId, complaintType, attestedDamage);
            hardGateFailures += IndependentEvalScorer
                    .checkContractHardGates(detCase, extCase, false)
                    .size();
        }

        // 2. Run REGEX_BASELINE and live LLM on dev & holdout text corpus, checking signal hard gates too.
        Map<String, Object> corpusDoc = OfflineAblationSmokeTest.readJson("eval/text-corpus.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textCases = (List<Map<String, Object>>) corpusDoc.get("cases");

        List<IndependentEvalScorer.TextCaseOutcome> regexDev = new ArrayList<>();
        List<IndependentEvalScorer.TextCaseOutcome> regexHold = new ArrayList<>();
        List<IndependentEvalScorer.TextCaseOutcome> llmDev = new ArrayList<>();
        List<IndependentEvalScorer.TextCaseOutcome> llmHold = new ArrayList<>();

        for (Map<String, Object> tc : textCases) {
            String caseId = String.valueOf(tc.get("caseId"));
            String split = String.valueOf(tc.get("split"));
            @SuppressWarnings("unchecked")
            List<String> expectedSignals = (List<String>) tc.get("expectedSignals");
            boolean expectedUntrusted = Boolean.TRUE.equals(tc.get("expectedUntrusted"));
            EvidenceSnapshot snap = OfflineAblationSmokeTest.snapshotFromTextCase(caseId, tc);

            RegexSignalBaseline.BaselineResult regexRes = regexBaseline.extract(snap);
            List<String> regexSignals = regexRes.signals().stream().map(f -> f.signalType().name()).toList();
            var regexOutcome = new IndependentEvalScorer.TextCaseOutcome(
                    caseId, split, expectedSignals, regexSignals,
                    expectedUntrusted, regexRes.untrustedTextDetected());

            UnstructuredExtractionPort.ExtractionOutcome llmRes = llmExtractor.extractWithLlm(snap);
            hardGateFailures += IndependentEvalScorer
                    .checkExtractedSignalsHardGates(snap, llmRes.unconfirmedSignals())
                    .size();
            List<String> llmSignals = llmRes.unconfirmedSignals().stream()
                    .map(m -> String.valueOf(m.get("signal_type"))).toList();
            var llmOutcome = new IndependentEvalScorer.TextCaseOutcome(
                    caseId, split, expectedSignals, llmSignals,
                    expectedUntrusted, llmRes.untrustedTextDetected());

            if ("dev".equals(split)) {
                regexDev.add(regexOutcome);
                llmDev.add(llmOutcome);
            } else {
                regexHold.add(regexOutcome);
                llmHold.add(llmOutcome);
            }
        }

        String report = AblationMarkdownReportGenerator.render(
                model, UnstructuredExtractor.promptHash(), false,
                fixtures.size(), hardGateFailures,
                IndependentEvalScorer.scoreTextSplit(regexDev),
                IndependentEvalScorer.scoreTextSplit(regexHold),
                IndependentEvalScorer.scoreTextSplit(llmDev),
                IndependentEvalScorer.scoreTextSplit(llmHold));

        Path reportPath = Path.of("target/ablation-live-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private static DeliveryCase runLiveFixtureCase(
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
                    new CaseApplicationService.AttestCommand(
                            "DAMAGE_LEVEL", attestedDamage, "eval-op", created.version()));
        }
        return created;
    }
}
