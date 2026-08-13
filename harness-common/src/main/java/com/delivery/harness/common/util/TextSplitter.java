package com.delivery.harness.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TextSplitter {

    private TextSplitter() {}

    public static List<String> splitByParagraph(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> result = new ArrayList<>();
        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static List<String> splitByFixedSize(String text, int chunkSize, int overlap) {
        if (text == null || text.trim().isEmpty() || chunkSize <= 0) {
            return Collections.emptyList();
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be greater than or equal to 0 and less than chunkSize");
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int step = chunkSize - overlap;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }
}
