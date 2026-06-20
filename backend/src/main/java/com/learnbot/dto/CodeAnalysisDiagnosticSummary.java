package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CodeAnalysisDiagnosticSummary(
        UUID id,
        UUID repositoryId,
        UUID indexVersion,
        String stage,
        String analyzer,
        String status,
        String mode,
        int attemptedFiles,
        int analyzedFiles,
        int failedFiles,
        int resolvedRelations,
        int unresolvedRelations,
        int nodeCount,
        int edgeCount,
        long durationMillis,
        String message,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {
}
