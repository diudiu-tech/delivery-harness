package com.delivery.harness.eval;

import com.delivery.harness.application.UnstructuredExtractionPort;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.extraction.ClaimVerifier;
import com.delivery.harness.extraction.RegexSignalBaseline;
import com.delivery.harness.extraction.UnstructuredExtractor;
import com.delivery.harness.llm.gateway.HttpLlmClient;
import com.delivery.harness.llm.gateway.LlmGateway;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.delivery.harness.llm.router.ModelRouter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void runsLiveModelAblationAndWritesMarkdownReport() throws IOException {
        String endpoint = System.getenv().getOrDefault("HARNESS_LLM_ENDPOINT", "http://localhost:11434");
        String model = System.getenv().getOrDefault("HARNESS_LLM_MODEL", "qwen2.5:7b");
        String apiKey = System.getenv().getOrDefault("HARNESS_LLM_API_KEY", "");

        HttpLlmClient httpClient = new HttpLlmClient();
        ReflectionTestUtils.setField(httpClient, "defaultEndpoint", endpoint);
        ReflectionTestUtils.setField(httpClient, "timeoutSeconds", 15);
        ReflectionTestUtils.setField(httpClient, "apiKey", apiKey);

        ModelRouter router = new ModelRouter();
        router.registerModel(UnstructuredExtractor.SCENARIO_EXTRACTION, model);
        ClaimVerifier verifier = new ClaimVerifier();
        RegexSignalBaseline regexBaseline = new RegexSignalBaseline(verifier);
        UnstructuredExtractor llmExtractor = new UnstructuredExtractor(
                new LlmGateway(router, httpClient), new StructuredOutputParser(), verifier, true);

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
                textCases.size(), 0,
                IndependentEvalScorer.scoreTextSplit(regexDev),
                IndependentEvalScorer.scoreTextSplit(regexHold),
                IndependentEvalScorer.scoreTextSplit(llmDev),
                IndependentEvalScorer.scoreTextSplit(llmHold));

        Path reportPath = Path.of("target/ablation-live-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println(report);
    }
}
