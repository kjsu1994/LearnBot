package com.learnbot.dto;

import java.util.Map;

public record AdminTuningUpdateRequest(
        String preset,
        String ollamaBaseUrl,
        String primaryChatModel,
        String auxiliaryChatModel,
        Map<String, Integer> values
) {
}
