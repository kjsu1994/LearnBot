package com.learnbot.dto;

import java.util.List;

public record AdminSettingsResponse(
        boolean respectRobotsTxt,
        List<String> allowedDomains,
        String ollamaBaseUrl,
        String chatModel,
        String primaryChatModel,
        String auxiliaryChatModel,
        String effectiveOllamaBaseUrl,
        String effectiveChatModel,
        String effectivePrimaryChatModel,
        String effectiveAuxiliaryChatModel,
        boolean llmUsingDefaults
) {
}
