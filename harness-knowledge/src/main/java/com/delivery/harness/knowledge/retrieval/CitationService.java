package com.delivery.harness.knowledge.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CitationService {

    public String buildCitationBlock(List<RetrievalService.RetrievalItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- 引用来源 ---\n");
        for (int i = 0; i < items.size(); i++) {
            RetrievalService.RetrievalItem item = items.get(i);
            sb.append(String.format("[%d] [%s] %s (ID: %s, Score: %.2f)\n",
                    i + 1, item.getSourceType(), item.getTitle(), item.getSourceId(), item.getScore()));
        }
        return sb.toString();
    }

    public String appendCitations(String content, List<RetrievalService.RetrievalItem> items) {
        return content + buildCitationBlock(items);
    }
}
