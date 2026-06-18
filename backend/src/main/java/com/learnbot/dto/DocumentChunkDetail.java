package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record DocumentChunkDetail(
        UUID id,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {
    public DocumentChunkDetail(
            UUID id,
            int chunkIndex,
            String content,
            OffsetDateTime createdAt
    ) {
        this(id, chunkIndex, content, Map.of(), createdAt);
    }
}
