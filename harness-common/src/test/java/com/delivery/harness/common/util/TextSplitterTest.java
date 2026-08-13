package com.delivery.harness.common.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextSplitterTest {

    @Test
    void returnsEmptyListsForNullOrBlankText() {
        assertAll(
                () -> assertEquals(Collections.emptyList(), TextSplitter.splitByParagraph(null)),
                () -> assertEquals(Collections.emptyList(), TextSplitter.splitByParagraph("")),
                () -> assertEquals(Collections.emptyList(), TextSplitter.splitByParagraph("  \n  ")),
                () -> assertEquals(Collections.emptyList(), TextSplitter.splitByFixedSize(null, 4, 0)),
                () -> assertEquals(Collections.emptyList(), TextSplitter.splitByFixedSize("  ", 4, 0))
        );
    }

    @Test
    void splitsParagraphsAndTrimsSurroundingWhitespace() {
        String text = "  First paragraph  \n\nSecond paragraph\n  \n  Third paragraph  ";

        assertEquals(
                Arrays.asList("First paragraph", "Second paragraph", "Third paragraph"),
                TextSplitter.splitByParagraph(text)
        );
    }

    @Test
    void splitsTextIntoFixedSizeChunks() {
        assertEquals(
                Arrays.asList("abcd", "efgh", "ij"),
                TextSplitter.splitByFixedSize("abcdefghij", 4, 0)
        );
    }

    @Test
    void preservesTheRequestedOverlapBetweenChunks() {
        assertEquals(
                Arrays.asList("abcd", "defg", "gh"),
                TextSplitter.splitByFixedSize("abcdefgh", 4, 1)
        );
    }

    @Test
    void rejectsNegativeOverlap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TextSplitter.splitByFixedSize("abcdefgh", 4, -1)
        );
    }

    @Test
    void rejectsOverlapEqualToOrGreaterThanChunkSize() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TextSplitter.splitByFixedSize("abcdefgh", 4, 4)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TextSplitter.splitByFixedSize("abcdefgh", 4, 5)
                )
        );
    }
}
