package com.delivery.harness.agent.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GuardrailChecker {

    @Value("${harness.guardrail.max-compensation-amount:50.0}")
    private double maxCompensationAmount;

    private static final List<String> FORBIDDEN_PHRASES = Arrays.asList(
            "保证赔偿", "一定赔偿", "必须全额退款", "我们承诺"
    );

    private static final Pattern SUGGESTED_AMOUNT_PATTERN = Pattern.compile(
            "\"suggested_amount\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)(?=\\s*[,}])");

    public boolean check(String content, String scenario) {
        if (content == null) {
            return false;
        }
        boolean phrasesPassed = checkForbiddenPhrases(content);
        boolean fieldsPassed = checkRequiredFields(content, scenario);
        return phrasesPassed && fieldsPassed;
    }

    public boolean checkCompensation(String content) {
        if (content == null) {
            return false;
        }
        boolean phrasesPassed = checkForbiddenPhrases(content);
        boolean amountPassed = checkAmountLimit(content);
        return phrasesPassed && amountPassed;
    }

    private boolean checkForbiddenPhrases(String content) {
        boolean passed = true;
        for (String phrase : FORBIDDEN_PHRASES) {
            if (content.contains(phrase)) {
                log.warn("Guardrail: forbidden phrase detected: {}", phrase);
                passed = false;
            }
        }
        return passed;
    }

    private boolean checkAmountLimit(String content) {
        Matcher matcher = SUGGESTED_AMOUNT_PATTERN.matcher(content);
        if (!matcher.find()) {
            log.warn("Guardrail: missing or invalid suggested_amount");
            return false;
        }

        double amount;
        try {
            amount = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            log.warn("Guardrail: invalid suggested_amount");
            return false;
        }
        if (!Double.isFinite(amount) || amount < 0 || amount > maxCompensationAmount) {
            log.warn("Guardrail: compensation amount {} is outside the allowed range [0, {}]",
                    amount, maxCompensationAmount);
            return false;
        }
        return true;
    }

    private boolean checkRequiredFields(String content, String scenario) {
        if ("abnormal_order_analysis".equals(scenario)) {
            if (!content.contains("primary_cause") && !content.contains("主要原因")) {
                log.warn("Guardrail: missing primary_cause in analysis output");
                return false;
            }
        }
        return true;
    }
}
