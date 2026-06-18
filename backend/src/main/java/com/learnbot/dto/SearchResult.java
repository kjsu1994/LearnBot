package com.learnbot.dto;

import java.util.Map;
import java.util.UUID;

public record SearchResult(
        UUID chunkId,
        UUID documentId,
        String title,
        String sourceUri,
        String sourceType,
        String contentType,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        double score
) {
    public SearchResult(
            UUID chunkId,
            UUID documentId,
            String title,
            String sourceUri,
            String sourceType,
            String contentType,
            int chunkIndex,
            String content,
            double score
    ) {
        this(chunkId, documentId, title, sourceUri, sourceType, contentType, chunkIndex, content, Map.of(), score);
    }
}
