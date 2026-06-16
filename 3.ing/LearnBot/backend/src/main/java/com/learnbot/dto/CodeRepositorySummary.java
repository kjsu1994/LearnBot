package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CodeRepositorySummary(
        UUID id,
        String name,
        String gitUrl,
        String branch,
        String authType,
        String status,
        String lastIndexedCommit,
        String errorMessage,
        int activeFileCount,
        int activeChunkCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
