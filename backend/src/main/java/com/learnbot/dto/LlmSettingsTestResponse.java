package com.learnbot.dto;

import java.util.List;

public record LlmSettingsTestResponse(
        boolean success,
        String message,
        String baseUrl,
        String model,
        String primaryModel,
        String auxiliaryModel,
        boolean defaultSettings,
        List<String> availableModels
) {
}
