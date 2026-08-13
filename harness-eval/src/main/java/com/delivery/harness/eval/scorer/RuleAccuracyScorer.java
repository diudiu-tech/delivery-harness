package com.delivery.harness.eval.scorer;

import com.delivery.harness.common.dto.EvalCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RuleAccuracyScorer {

    public double score(EvalCase evalCase, Map<String, Object> actualOutput) {
        List<String> expectedRuleIds = evalCase.getExpectedRuleIds();
        if (expectedRuleIds == null || expectedRuleIds.isEmpty()) {
            return 1.0;
        }

        String outputStr = actualOutput.toString().toLowerCase();
        long matched = expectedRuleIds.stream()
                .filter(ruleId -> outputStr.contains(ruleId.toLowerCase()))
                .count();

        double score = (double) matched / expectedRuleIds.size();
        log.debug("RuleAccuracy: expected={}, matched={}, score={}", expectedRuleIds.size(), matched, score);
        return score;
    }
}
