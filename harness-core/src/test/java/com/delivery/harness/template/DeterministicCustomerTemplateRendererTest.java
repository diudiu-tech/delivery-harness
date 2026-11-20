package com.delivery.harness.template;

import com.delivery.harness.policy.AdjudicationStatus;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicCustomerTemplateRendererTest {

    private static final List<String> COMPLETED_WORDS = List.of("已完成赔付", "已退款", "已发券", "已为您处理");
    private static final List<String> FORBIDDEN_PROMISES = List.of("保证赔偿", "一定赔偿", "必须全额退款", "我们承诺");

    private final DeterministicCustomerTemplateRenderer renderer = new DeterministicCustomerTemplateRenderer();

    @Test
    void neverClaimsCompletionWithoutAnExternalExecutionReceipt() {
        SingleSourcePolicyEngine.AdjudicationResult eligible = new SingleSourcePolicyEngine.AdjudicationResult(
                AdjudicationStatus.ELIGIBLE, "policy-v1.0.0", "hash",
                "COMP-002", "一般超时赔付", "desc",
                new BigDecimal("5.00"), new BigDecimal("5.00"),
                "发放优惠券", false, List.of(), List.of(), List.of());

        CustomerDraft pending = renderer.render(eligible, false, null);
        CustomerDraft executed = renderer.render(eligible, true, "RCPT-2026-001");

        assertAll(
                () -> assertFalse(pending.executionCompleted()),
                () -> assertFalse(pending.sendableDirectly()),
                () -> assertTrue(pending.message().contains("5.00 元")),
                () -> assertTrue(pending.message().contains("尚待人工审核确认")),
                () -> assertNoWords(pending.message(), COMPLETED_WORDS),
                () -> assertNoWords(pending.message(), FORBIDDEN_PROMISES),
                () -> assertTrue(executed.executionCompleted()),
                () -> assertTrue(executed.message().contains("已完成赔付处理")),
                () -> assertTrue(executed.message().contains("RCPT-2026-001")),
                () -> assertNoWords(executed.message(), FORBIDDEN_PROMISES));
    }

    @Test
    void neverDisplaysAmountsOnNonActionableAdjudicationStatuses() {
        List<SingleSourcePolicyEngine.AdjudicationResult> nonActionable = List.of(
                resultWith(AdjudicationStatus.NEEDS_EVIDENCE, List.of("DAMAGE_LEVEL")),
                resultWith(AdjudicationStatus.EXCEEDS_CAP_ESCALATE, List.of()),
                resultWith(AdjudicationStatus.NO_APPLICABLE_POLICY, List.of()),
                resultWith(AdjudicationStatus.OUT_OF_SCOPE, List.of()));

        for (SingleSourcePolicyEngine.AdjudicationResult adj : nonActionable) {
            CustomerDraft draft = renderer.render(adj, false, null);
            assertAll(adj.status().name(),
                    () -> assertFalse(draft.executionCompleted()),
                    () -> assertFalse(draft.message().contains("元"), draft.message()),
                    () -> assertFalse(draft.message().contains("¥"), draft.message()),
                    () -> assertFalse(draft.message().contains("￥"), draft.message()),
                    () -> assertNoWords(draft.message(), COMPLETED_WORDS),
                    () -> assertNoWords(draft.message(), FORBIDDEN_PROMISES));
        }
    }

    @Test
    void rendersHonestIneligibleMessageForOnTimeOrder() {
        CustomerDraft draft = renderer.render(resultWith(AdjudicationStatus.INELIGIBLE, List.of()), false, null);
        assertAll(
                () -> assertFalse(draft.executionCompleted()),
                () -> assertTrue(draft.message().contains("未超过承诺时间")),
                () -> assertNoWords(draft.message(), COMPLETED_WORDS),
                () -> assertNoWords(draft.message(), FORBIDDEN_PROMISES));
    }

    private static SingleSourcePolicyEngine.AdjudicationResult resultWith(
            AdjudicationStatus status, List<String> missing) {
        return new SingleSourcePolicyEngine.AdjudicationResult(
                status, "policy-v1.0.0", "hash",
                null, null, null, null, null, null,
                true, List.of(), missing, List.of());
    }

    private static void assertNoWords(String text, List<String> words) {
        for (String word : words) {
            assertFalse(text.contains(word), "Text must not contain '" + word + "': " + text);
        }
    }
}
