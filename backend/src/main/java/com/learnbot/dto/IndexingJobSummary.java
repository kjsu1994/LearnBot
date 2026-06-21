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
        int addedFiles,
        int modifiedFiles,
        int unchangedFiles,
        int deletedFiles,
        String commitHash,
        String errorMessage,
        OffsetDateTime searchableAt,
        String enrichmentStatus,
        String enrichmentMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt
) {
}
