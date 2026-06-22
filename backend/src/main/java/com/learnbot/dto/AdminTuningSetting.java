package com.learnbot.dto;

public record AdminTuningSetting(
        String key,
        String label,
        String description,
        String category,
        String control,
        int value,
        int effectiveValue,
        int defaultValue,
        int min,
        int max,
        int step,
        boolean restartRequired,
        String impact,
        String envKey
) {
}
