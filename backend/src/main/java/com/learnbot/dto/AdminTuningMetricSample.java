package com.learnbot.dto;

import java.time.Instant;

public record AdminTuningMetricSample(
        Instant createdAt,
        String domain,
        String mode,
        long totalMs,
        long llmMs,
        long searchMs,
        long embeddingMs,
        long rerankMs,
        long contextMs,
        int promptTokenBudget,
        int promptEvalTokens,
        int outputTokens,
        int contextChunkCount,
        int queryCount,
        boolean fallbackUsed,
        boolean llmUnavailable,
        String profile
) {
}
