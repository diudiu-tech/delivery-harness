package com.delivery.harness.agent.formatter;

import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class OutputFormatter {

    public Map<String, Object> formatAbnormalOrderAnalysis(String orderId, String llmContent,
                                                            RetrievalService.RetrievalResult retrievalResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", orderId);
        result.put("analysis_type", "abnormal_order");
        result.put("llm_analysis", llmContent);
        result.put("citations", buildCitations(retrievalResult));
        result.put("needs_human_review", true);
        result.put("generated_at", new Date());
        return result;
    }

    public Map<String, Object> formatCompensationSuggestion(String orderId, String complaintType,
                                                              String llmContent, ToolResult compResult,
                                                              RetrievalService.RetrievalResult retrievalResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", orderId);
        result.put("complaint_type", complaintType);
        result.put("analysis_type", "compensation_suggestion");
        result.put("rule_match_result", compResult.getData());
        result.put("llm_suggestion", llmContent);
        result.put("citations", buildCitations(retrievalResult));
        result.put("approval_required", true);
        result.put("generated_at", new Date());
        return result;
    }

    private List<Map<String, Object>> buildCitations(RetrievalService.RetrievalResult retrievalResult) {
        List<Map<String, Object>> citations = new ArrayList<>();
        if (retrievalResult != null && retrievalResult.getItems() != null) {
            for (int i = 0; i < retrievalResult.getItems().size(); i++) {
                RetrievalService.RetrievalItem item = retrievalResult.getItems().get(i);
                Map<String, Object> citation = new HashMap<>();
                citation.put("index", i + 1);
                citation.put("source_type", item.getSourceType());
                citation.put("source_id", item.getSourceId());
                citation.put("title", item.getTitle());
                citation.put("score", item.getScore());
                citations.add(citation);
            }
        }
        return citations;
    }
}
