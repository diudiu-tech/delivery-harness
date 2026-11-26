package com.delivery.harness.knowledge.ingestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    private String chunkId;
    private String documentId;
    private Integer chunkIndex;
    private String content;
    private String sourceType;
    private String category;
    private List<String> tags;
    private float[] vector;
    private LocalDateTime createdAt;
}
