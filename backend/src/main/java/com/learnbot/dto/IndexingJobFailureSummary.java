package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IndexingJobFailureSummary(
        UUID id,
        UUID jobId,
        UUID repositoryId,
        String filePath,
        String stage,
        String message,
        OffsetDateTime createdAt
) {
}

