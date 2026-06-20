package com.learnbot.dto;

import java.util.Map;
import java.util.UUID;

public record AnswerEvidence(
        int citationNumber,
        UUID chunkId,
        UUID documentId,
        String title,
        String sourceUri,
        String sourceType,
        int chunkIndex,
        String preview,
        double score,
        Map<String, Object> metadata
) {
    public AnswerEvidence(
            int citationNumber,
            UUID chunkId,
            UUID documentId,
            String title,
            String sourceUri,
            String sourceType,
            int chunkIndex,
            String preview,
            double score
    ) {
        this(citationNumber, chunkId, documentId, title, sourceUri, sourceType, chunkIndex, preview, score, Map.of());
    }
}
