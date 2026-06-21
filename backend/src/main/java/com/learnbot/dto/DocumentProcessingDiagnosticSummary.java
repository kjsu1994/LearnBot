package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record DocumentProcessingDiagnosticSummary(
        UUID id,
        UUID sourceId,
        UUID jobId,
        String stage,
        String analyzer,
        String status,
        String mode,
        int attemptedItems,
        int processedItems,
        int failedItems,
        int nodeCount,
        int edgeCount,
        long durationMillis,
        String message,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {
}
