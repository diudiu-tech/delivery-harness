package com.delivery.harness.knowledge.ingestion;

import com.delivery.harness.common.util.TextSplitter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;

    public KnowledgeDocument ingest(IngestRequest request) {
        validateRequest(request);

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .title(request.getTitle())
                .sourceType(request.getSourceType())
                .category(request.getCategory())
                .content(request.getContent())
                .tags(request.getTags())
                .createdAt(LocalDateTime.now())
                .build();

        documentRepository.save(doc);
        log.info("Document ingested: id={}, title={}", doc.getDocumentId(), doc.getTitle());

        List<KnowledgeChunk> chunks = splitAndStore(doc, request.getChunkSize(), request.getChunkOverlap());
        log.info("Document chunked: id={}, chunks={}", doc.getDocumentId(), chunks.size());

        return doc;
    }

    private List<KnowledgeChunk> splitAndStore(KnowledgeDocument doc, Integer chunkSize, Integer overlap) {
        List<String> textChunks;
        if (chunkSize != null && chunkSize > 0) {
            int effectiveOverlap = overlap != null ? overlap : Math.min(100, chunkSize / 10);
            textChunks = TextSplitter.splitByFixedSize(doc.getContent(), chunkSize, effectiveOverlap);
        } else {
            textChunks = TextSplitter.splitByParagraph(doc.getContent());
        }

        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            KnowledgeChunk chunk = KnowledgeChunk.builder()
                    .chunkId(UUID.randomUUID().toString())
                    .documentId(doc.getDocumentId())
                    .chunkIndex(i)
                    .content(textChunks.get(i))
                    .sourceType(doc.getSourceType())
                    .category(doc.getCategory())
                    .tags(doc.getTags())
                    .createdAt(LocalDateTime.now())
                    .build();
            chunks.add(chunk);
        }

        chunkRepository.saveAll(chunks);
        return chunks;
    }

    private void validateRequest(IngestRequest request) {
        if (request == null || request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (request.getChunkSize() == null && request.getChunkOverlap() != null) {
            throw new IllegalArgumentException("chunkSize is required when chunkOverlap is provided");
        }
        if (request.getChunkSize() != null) {
            int overlap = request.getChunkOverlap() != null
                    ? request.getChunkOverlap()
                    : Math.min(100, request.getChunkSize() / 10);
            if (overlap < 0 || overlap >= request.getChunkSize()) {
                throw new IllegalArgumentException("chunkOverlap must be less than chunkSize");
            }
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class IngestRequest {
        @NotBlank
        @Size(max = 200)
        private String title;

        @NotBlank
        @Size(max = 50)
        private String sourceType;

        @Size(max = 100)
        private String category;

        @NotBlank
        @Size(max = 100_000)
        private String content;

        @Size(max = 20)
        private List<String> tags;

        @Min(100)
        @Max(10_000)
        private Integer chunkSize;

        @Min(0)
        @Max(9_999)
        private Integer chunkOverlap;
    }
}
