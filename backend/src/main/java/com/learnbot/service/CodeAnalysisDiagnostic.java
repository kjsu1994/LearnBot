package com.learnbot.service;

import java.util.Map;

public record CodeAnalysisDiagnostic(
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
        Map<String, Object> metadata
) {
    public static CodeAnalysisDiagnostic skipped(String stage, String analyzer, String mode, String message) {
        return new CodeAnalysisDiagnostic(stage, analyzer, "SKIPPED", mode, 0, 0, 0, 0, 0, 0, 0, 0, message, Map.of());
    }
}
