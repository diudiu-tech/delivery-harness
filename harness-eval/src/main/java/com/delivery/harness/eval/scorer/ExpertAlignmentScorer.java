package com.delivery.harness.eval.scorer;

import com.delivery.harness.common.dto.EvalCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ExpertAlignmentScorer {

    public double score(EvalCase evalCase, Map<String, Object> actualOutput) {
        String expertAnswer = evalCase.getExpertAnswer();
        if (expertAnswer == null || expertAnswer.trim().isEmpty()) {
            return 1.0;
        }

        String outputStr = actualOutput.toString().toLowerCase();
        String[] keywords = expertAnswer.toLowerCase().split("[,，；;\\s]+");

        long matched = 0;
        for (String keyword : keywords) {
            if (!keyword.trim().isEmpty() && outputStr.contains(keyword.trim())) {
                matched++;
            }
        }

        double score = keywords.length > 0 ? (double) matched / keywords.length : 0.0;
        log.debug("ExpertAlignment: keywords={}, matched={}, score={}", keywords.length, matched, score);
        return Math.min(score, 1.0);
    }
}
