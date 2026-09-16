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

    /**
     * Currency expressions in prose, such as 20元, ￥20 or ¥ 20.00.
     * Possessive digit runs avoid backtracking; the suffix branch must not
     * retry from every digit inside a long number when no currency follows.
     */
    private static final Pattern CURRENCY_AMOUNT_MENTION = Pattern.compile(
            "(?:[¥￥]\\s*(-?(?:\\d++(?:\\.\\d*+)?|\\.\\d++))|(?<!\\d)-?(?:\\d++(?:\\.\\d*+)?|\\.\\d++)\\s*元)");

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
     * True when the model contains a currency amount or a suggested amount
     * field. This is retained as a broad detector for callers that need to
     * know whether amount-like content was present.
     */
    public boolean mentionsCompensationAmount(String content) {
        return content != null
                && (AMOUNT_FIELD_MENTION.matcher(content).find()
                || CURRENCY_AMOUNT_MENTION.matcher(content).find());
    }

    /**
     * True when an amount in the model output conflicts with the authoritative
     * rule-engine amount. Repeating the approved amount in an explanation is
     * harmless; a different number is a real policy conflict. A malformed
     * amount field still fails closed because it cannot be compared safely.
     */
    public boolean mentionsConflictingCompensationAmount(String content, BigDecimal authoritativeAmount) {
        if (content == null || !mentionsCompensationAmount(content)) {
            return false;
        }
        if (authoritativeAmount == null) {
            return true;
        }

        boolean comparableAmountFound = false;
        Matcher fieldMatcher = SUGGESTED_AMOUNT_PATTERN.matcher(content);
        while (fieldMatcher.find()) {
            comparableAmountFound = true;
            if (differs(fieldMatcher.group(1), authoritativeAmount)) {
                return true;
            }
        }

        Matcher currencyMatcher = CURRENCY_AMOUNT_MENTION.matcher(content);
        while (currencyMatcher.find()) {
            String numericText = currencyMatcher.group(1);
            if (numericText == null) {
                String match = currencyMatcher.group();
                int numberStart = 0;
                while (numberStart < match.length()
                        && (match.charAt(numberStart) == '￥' || match.charAt(numberStart) == '¥'
                        || Character.isWhitespace(match.charAt(numberStart)))) {
                    numberStart++;
                }
                numericText = match.substring(numberStart).replace("元", "").trim();
            }
            comparableAmountFound = true;
            if (differs(numericText, authoritativeAmount)) {
                return true;
            }
        }

        // The field name was present, but its value was not a comparable
        // number (for example {"suggested_amount":"20"}).
        return AMOUNT_FIELD_MENTION.matcher(content).find() && !comparableAmountFound;
    }

    private static boolean differs(String candidate, BigDecimal authoritativeAmount) {
        try {
            return new BigDecimal(candidate).compareTo(authoritativeAmount) != 0;
        } catch (NumberFormatException e) {
            log.warn("Guardrail: unparseable compensation amount treated as a conflict");
            return true;
        }
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
