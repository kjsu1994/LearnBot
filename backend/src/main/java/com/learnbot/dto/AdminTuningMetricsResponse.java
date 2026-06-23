package com.learnbot.dto;

import java.time.Instant;
import java.util.List;

public record AdminTuningMetricsResponse(
        Instant generatedAt,
        int windowSize,
        AdminTuningMetricsSummary summary,
        AdminTuningOllamaStatus ollama,
        AdminTuningRerankerStatus reranker,
        List<AdminTuningMetricSample> recent
) {
}
