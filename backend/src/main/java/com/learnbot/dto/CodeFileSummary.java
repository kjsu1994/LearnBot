package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CodeFileSummary(
        UUID id,
        UUID repositoryId,
        String filePath,
        String language,
        String contentHash,
        int chunkCount,
        OffsetDateTime updatedAt
) {
}
