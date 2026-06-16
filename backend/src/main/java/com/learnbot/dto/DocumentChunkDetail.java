package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentChunkDetail(
        UUID id,
        int chunkIndex,
        String content,
        OffsetDateTime createdAt
) {
}
