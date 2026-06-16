package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IndexingJobSummary(
        UUID id,
        UUID repositoryId,
        String jobType,
        String status,
        int totalFiles,
        int processedFiles,
        int totalChunks,
        int failedFiles,
        String commitHash,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt
) {
}
