package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentIndexingJobSummary(
        UUID id,
        UUID sourceId,
        UUID spaceId,
        String jobType,
        String status,
        int totalDocuments,
        int processedDocuments,
        int totalChunks,
        int reusedChunks,
        int embeddedChunks,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt
) {
}
