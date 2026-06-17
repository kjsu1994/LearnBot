package com.learnbot.dto;

public record LlmSettingsTestRequest(
        String ollamaBaseUrl,
        String chatModel,
        String primaryChatModel,
        String auxiliaryChatModel
) {
}
