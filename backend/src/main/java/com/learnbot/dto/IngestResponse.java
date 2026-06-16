package com.learnbot.dto;

import java.util.UUID;

public record IngestResponse(
        UUID sourceId,
        UUID documentId,
        UUID spaceId,
        int chunkCount,
        String status,
        int documentCount,
        int pageCount,
        int skippedCount
) {
    public IngestResponse(UUID sourceId, UUID documentId, UUID spaceId, int chunkCount, String status) {
        this(sourceId, documentId, spaceId, chunkCount, status, 1, 1, 0);
    }
}
