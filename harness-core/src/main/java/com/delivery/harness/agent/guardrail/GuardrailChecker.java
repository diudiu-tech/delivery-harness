package com.delivery.harness.agent.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Advisory output checks.
 *
 * <p>Not a policy engine. It detects a small set of promise-like phrases,
 * bounds a payout, and verifies a required field is present. Results are
 * reported on the response and in the workflow steps; they never authorise a
 * payment, and human review is still required.
 */
@Slf4j
@Component
public class GuardrailChecker {

    private static final List<String> FORBIDDEN_PHRASES = Arrays.asList(
            "保证赔偿", "一定赔偿", "必须全额退款", "我们承诺"
    );

    private static final Pattern SUGGESTED_AMOUNT_PATTERN = Pattern.compile(
            "\"suggested_amount\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)(?=\\s*[,}])");

    /** Any mention of the amount field, whether or not the value parses. */
    private static final Pattern AMOUNT_FIELD_MENTION = Pattern.compile("\"?suggested_amount\"?\\s*[:：]");

    @Value("${harness.guardrail.max-compensation-amount:50.0}")
    private double maxCompensationAmount;

    public boolean check(String content, String scenario) {
        if (content == null) {
            return false;
        }
        boolean phrasesPassed = checkForbiddenPhrases(content);
        boolean fieldsPassed = checkRequiredFields(content, scenario);
        return phrasesPassed && fieldsPassed;
    }

    /**
     * Validates a payload that carries its own {@code suggested_amount}.
     *
     * <p>Retained for callers holding a serialised decision. The compensation
     * workflow no longer routes the model's text through here: it checks the
     * rule engine's amount via {@link #checkCompensationAmount(BigDecimal)},
     * because that is the number the response actually carries.
     */
    public boolean checkCompensation(String content) {
        if (content == null) {
            return false;
        }
        boolean phrasesPassed = checkForbiddenPhrases(content);
        boolean amountPassed = checkAmountLimit(content);
        return phrasesPassed && amountPassed;
    }

    /** Bounds the authoritative payout to the closed range [0, max]. */
    public boolean checkCompensationAmount(BigDecimal amount) {
        if (amount == null) {
            log.warn("Guardrail: no compensation amount to check");
            return false;
        }
        double value = amount.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value > maxCompensationAmount) {
            log.warn("Guardrail: compensation amount {} is outside the allowed range [0, {}]",
                    amount.toPlainString(), maxCompensationAmount);
            return false;
        }
        return true;
    }

    /** True when the text contains none of the promise-like phrases. */
    public boolean checkForbiddenPhrases(String content) {
        if (content == null) {
            return false;
        }
        boolean passed = true;
        for (String phrase : FORBIDDEN_PHRASES) {
            if (content.contains(phrase)) {
                log.warn("Guardrail: forbidden phrase detected: {}", phrase);
                passed = false;
            }
        }
        return passed;
    }

    /**
     * True when the model emitted a {@code suggested_amount} despite being
     * instructed not to. The rule engine owns the amount, so a model that
     * proposes one is disregarding its instructions and the output should be
     * flagged for review rather than trusted.
     */
    public boolean mentionsAmountField(String content) {
        return content != null && AMOUNT_FIELD_MENTION.matcher(content).find();
    }

    private boolean checkAmountLimit(String content) {
        Matcher matcher = SUGGESTED_AMOUNT_PATTERN.matcher(content);
        if (!matcher.find()) {
            log.warn("Guardrail: missing or invalid suggested_amount");
            return false;
        }
        try {
            return checkCompensationAmount(new BigDecimal(matcher.group(1)));
        } catch (NumberFormatException e) {
            log.warn("Guardrail: invalid suggested_amount");
            return false;
        }
    }

    private boolean checkRequiredFields(String content, String scenario) {
        if ("abnormal_order_analysis".equals(scenario)
                && !content.contains("primary_cause") && !content.contains("主要原因")) {
            log.warn("Guardrail: missing primary_cause in analysis output");
            return false;
        }
        return true;
    }
}
