package com.learnbot.dto.interactive;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeAgentInteractiveContextReadResultRequest(
        @NotNull UUID repositoryId,
        @NotNull UUID conversationId,
        @NotNull UUID turnId,
        UUID spaceId,
        UUID agentId,
        UUID workspaceId,
        List<Map<String, Object>> files,
        List<Map<String, Object>> toolResults,
        List<String> warnings
) {
}
