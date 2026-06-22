package com.learnbot.dto;

import java.util.Map;

public record AdminTuningPreset(
        String id,
        String label,
        String description,
        Map<String, Integer> values
) {
}
