package com.delivery.harness.eval.evaluator;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The overall score averages only the metrics that were actually measured.
 *
 * <p>Previously every scorer returned 1.0 for an absent expectation, so the
 * mean of three unmeasured metrics was a perfect 1.0 and adding unlabelled
 * cases raised the suite average.
 */
class OverallScoreTest {

    @Test
    void averagesOnlyTheMeasuredMetrics() {
        Double overall = OfflineEvaluator.mean(
                OptionalDouble.of(1.0), OptionalDouble.empty(), OptionalDouble.of(0.0));

        assertEquals(0.5d, overall);
    }

    @Test
    void isNullWhenNothingWasMeasured() {
        Double overall = OfflineEvaluator.mean(
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());

        assertNull(overall, "reporting no score is honest; reporting 1.0 was not");
    }

    @Test
    void averagesAllThreeWhenAllArePresent() {
        Double overall = OfflineEvaluator.mean(
                OptionalDouble.of(1.0), OptionalDouble.of(0.5), OptionalDouble.of(0.0));

        assertEquals(0.5d, overall);
    }
}
