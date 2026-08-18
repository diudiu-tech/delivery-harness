package com.delivery.harness.gateway.controller;

import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.knowledge.ingestion.KnowledgeDocument;
import com.delivery.harness.knowledge.ingestion.KnowledgeIngestionService;
import com.delivery.harness.knowledge.retrieval.RetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final RetrievalService retrievalService;

    @PostMapping("/ingest")
    public HarnessResponse<KnowledgeDocument> ingest(
            @Valid @RequestBody KnowledgeIngestionService.IngestRequest request) {
        KnowledgeDocument doc = ingestionService.ingest(request);
        return HarnessResponse.success(doc);
    }

    @PostMapping("/search")
    public HarnessResponse<RetrievalService.RetrievalResult> search(
            @Valid @RequestBody RetrievalService.RetrievalRequest request) {
        RetrievalService.RetrievalResult result = retrievalService.retrieve(request);
        return HarnessResponse.success(result);
    }
}
