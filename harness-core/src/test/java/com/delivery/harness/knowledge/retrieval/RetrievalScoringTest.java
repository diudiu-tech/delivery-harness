package com.delivery.harness.knowledge.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the scoring primitives that replaced constant scores.
 *
 * <p>The old implementation gave every rule 0.8, every document 0.6 and every
 * case 0.5, and matched by testing whether the whole query string appeared
 * inside an item — which fails for any query with more than one term. These
 * tests pin the two properties that fixed: multi-term queries match, and the
 * score reflects how much of the query matched.
 */
class RetrievalScoringTest {

    @Test
    void splitsMultiTermQueries() {
        assertEquals(Set.of("超时归因", "商家出餐慢"),
                RetrievalService.tokenize("超时归因 商家出餐慢"));
    }

    @Test
    void splitsOnPunctuationAsWellAsWhitespace() {
        assertEquals(Set.of("超时", "赔付", "严重"),
                RetrievalService.tokenize("超时，赔付、严重"));
    }

    @Test
    void dropsSingleCharacterAndEmptyTerms() {
        Set<String> terms = RetrievalService.tokenize("超时 a 归因");

        assertAllOf(terms, "超时", "归因");
        assertFalse(terms.contains("a"), "single characters match almost anything");
    }

    @Test
    void treatsAnEmptyQueryAsMatchingNothing() {
        assertTrue(RetrievalService.tokenize("   ").isEmpty());
        assertEquals(0d, RetrievalService.overlap(Set.of(), "任意内容"));
    }

    @Test
    void scoresByFractionOfQueryTermsPresent() {
        Set<String> terms = RetrievalService.tokenize("超时归因 商家出餐慢");

        assertEquals(1.0d, RetrievalService.overlap(terms,
                "超时归因-商家出餐慢", "骑手到店后等待时长超过均值2倍"));
        assertEquals(0.5d, RetrievalService.overlap(terms,
                "超时归因-运力不足", "站点运力比超过0.85"));
        assertEquals(0.0d, RetrievalService.overlap(terms, "错单赔付", "送错订单优先补送"));
    }

    @Test
    void searchesEverySuppliedFieldAndIgnoresNulls() {
        Set<String> terms = RetrievalService.tokenize("暴雨 归因");

        assertEquals(1.0d, RetrievalService.overlap(terms, null, "暴雨预警", null, "归因"));
    }

    @Test
    void matchesCaseInsensitively() {
        assertEquals(1.0d, RetrievalService.overlap(RetrievalService.tokenize("OVERTIME"), "overtime rule"));
    }

    private static void assertAllOf(Set<String> actual, String... expected) {
        for (String term : expected) {
            assertTrue(actual.contains(term), "expected term: " + term);
        }
    }
}
