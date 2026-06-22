package com.learnbot.dto;

import java.util.List;

public record AdminTuningResponse(
        String activePreset,
        boolean usingDefaults,
        String ollamaBaseUrl,
        String primaryChatModel,
        String auxiliaryChatModel,
        String effectiveOllamaBaseUrl,
        String effectivePrimaryChatModel,
        String effectiveAuxiliaryChatModel,
        List<AdminTuningSetting> settings,
        List<AdminTuningPreset> presets,
        List<String> warnings
) {
}
