package com.learnbot.dto;

public record AdminTuningMetricsSummary(
        int requestCount,
        long avgTotalMs,
        long p95TotalMs,
        int avgLlmSharePercent,
        long avgSearchMs,
        long avgEmbeddingMs,
        long avgRerankMs,
        int avgContextChunkCount,
        int avgPromptTokens,
        int promptTokenBudget,
        int fallbackCount
) {
}
