package com.learnbot.dto;

import java.time.Instant;
import java.util.Map;

public record AdminTuningRerankerStatus(
        boolean enabled,
        boolean warmupOnStartup,
        int idleUnloadSeconds,
        String baseUrl,
        String serviceStatus,
        boolean modelLoaded,
        boolean modelLoading,
        int activeRequests,
        String modelName,
        String device,
        Long cudaAllocatedBytes,
        Long cudaReservedBytes,
        Instant lastUsedAt,
        Instant lastUnloadAt,
        String lastError,
        Map<String, Object> raw
) {
}
