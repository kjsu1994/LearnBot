package com.learnbot.dto;

import java.util.List;

public record AdminTuningOllamaStatus(
        String baseUrl,
        String primaryModel,
        String auxiliaryModel,
        int primaryInFlight,
        int embeddingInFlight,
        int configuredParallel,
        int estimatedQueue,
        int maxLoadedModels,
        int contextLength,
        String gpuMode,
        List<String> loadedModels
) {
}
