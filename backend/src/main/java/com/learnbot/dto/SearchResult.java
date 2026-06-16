package com.learnbot.dto;

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
        double score
) {
}
