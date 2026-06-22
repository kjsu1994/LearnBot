package com.learnbot.dto;

public record AdminTuningRecommendationChange(
        String key,
        int currentValue,
        int recommendedValue,
        String reason,
        String risk,
        boolean requiresRestart
) {
}
