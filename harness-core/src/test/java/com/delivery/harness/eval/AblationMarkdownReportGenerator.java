package com.delivery.harness.eval;

import java.util.Locale;

/**
 * Renders a PR-ready Markdown ablation report comparing
 * {@code DETERMINISTIC_ONLY}, {@code REGEX_BASELINE}, and
 * {@code DETERMINISTIC_PLUS_EXTRACT}, including explicit stub banners and the
 * ADR-0005 exit clause.
 */
final class AblationMarkdownReportGenerator {

    private AblationMarkdownReportGenerator() {}

    static String render(
            String modelId,
            String promptHash,
            boolean isStub,
            int contractFixturesChecked,
            int contractHardGateFailures,
            IndependentEvalScorer.TextSplitMetrics regexDev,
            IndependentEvalScorer.TextSplitMetrics regexHoldout,
            IndependentEvalScorer.TextSplitMetrics llmDev,
            IndependentEvalScorer.TextSplitMetrics llmHoldout) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Delivery Harness Ablation & Hard-Gate Report\n\n");
        if (isStub) {
            sb.append("> **PIPELINE_SMOKE_ONLY（非模型质量）**：本次运行使用 `StubLlmClient`，仅证明管线连通与硬门禁有效，不得与真实模型数据混排或用于声明模型准确率。\n\n");
        }
        sb.append("- `modelId`: `").append(modelId).append("`\n");
        sb.append("- `promptHash`: `").append(promptHash).append("`\n");
        sb.append("- Contract Fixtures Checked: `").append(contractFixturesChecked)
                .append("`, Hard-Gate Failures: `").append(contractHardGateFailures).append("`\n\n");

        sb.append("## Holdout Split Comparison (`text-corpus.json`, n=")
                .append(regexHoldout.sampleCount()).append(")\n\n");
        sb.append("| Mode | Signal Precision | Signal Recall | F1 | Conflict Recall | Exemption FP (Veto) | Injection Caught | Hard Gate |\n");
        sb.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        sb.append("| `DETERMINISTIC_ONLY` | n/a | 0.00 | 0.00 | 0.00 | 0 | ")
                .append(regexHoldout.untrustedDetected()).append("/").append(regexHoldout.untrustedExpected())
                .append(" | PASS (baseline) |\n");
        appendRow(sb, "REGEX_BASELINE (20-line)", regexHoldout);
        appendRow(sb, isStub ? "STUB_LLM_EXTRACT (smoke)" : "LLM_EXTRACT", llmHoldout);

        sb.append("\n## Dev Split Reference (n=").append(regexDev.sampleCount()).append(")\n\n");
        sb.append("| Mode | Signal Precision | Signal Recall | F1 | Conflict Recall | Exemption FP (Veto) | Injection Caught | Hard Gate |\n");
        sb.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        appendRow(sb, "REGEX_BASELINE (20-line)", regexDev);
        appendRow(sb, isStub ? "STUB_LLM_EXTRACT (smoke)" : "LLM_EXTRACT", llmDev);

        sb.append("\n## Decision Verdict (ADR-0005 Exit Clause)\n\n");
        if (isStub) {
            sb.append("- **PIPELINE_SMOKE_ONLY**: 管线与独立硬门禁验证通过；真实模型增益结论须通过 `@Tag(\"live-llm\")` 离线执行后产出。\n");
        } else if (!llmHoldout.hardGatePassed() || contractHardGateFailures > 0) {
            sb.append("- **REJECT**: 触发硬门禁否决（免责类假阳性或合同门禁失败），禁止启用 LLM 提取。\n");
        } else if (llmHoldout.f1() <= regexHoldout.f1()) {
            sb.append("- **EXIT CLAUSE TRIGGERED**: 当前模型未在 holdout 集上稳定胜过 20 行正则基线（LLM F1=")
                    .append(fmt(llmHoldout.f1())).append(" <= Regex F1=").append(fmt(regexHoldout.f1()))
                    .append("），不建议在提取链路启用 LLM。\n");
        } else {
            sb.append("- **CANDIDATE GAIN OBSERVED**: 模型通过全部硬门禁且在 holdout 集上优于正则基线（LLM F1=")
                    .append(fmt(llmHoldout.f1())).append(" vs Regex F1=").append(fmt(regexHoldout.f1()))
                    .append("），样本量 n=").append(llmHoldout.sampleCount())
                    .append("（人工扩展真实样本后复验）。\n");
        }
        return sb.toString();
    }

    private static void appendRow(
            StringBuilder sb, String name, IndependentEvalScorer.TextSplitMetrics m) {
        sb.append("| `").append(name).append("` | ")
                .append(fmt(m.precision())).append(" | ")
                .append(fmt(m.recall())).append(" | ")
                .append(fmt(m.f1())).append(" | ")
                .append(fmt(m.conflictDetectionRecall())).append(" | ")
                .append(m.exemptionFalsePositives()).append(" | ")
                .append(m.untrustedDetected()).append("/").append(m.untrustedExpected()).append(" | ")
                .append(m.hardGatePassed() ? "PASS" : "FAIL (VETO)")
                .append(" |\n");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
