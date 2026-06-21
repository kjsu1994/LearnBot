package com.learnbot.service;

import java.util.Map;

public record DocumentProcessingDiagnostic(
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
        Map<String, Object> metadata
) {
}
