package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record LocalAgentReadOnlyToolRequest(
        @NotNull UUID agentId,
        @NotNull UUID workspaceId,
        @NotNull LocalAgentToolName toolName,
        Map<String, Object> input
) {
}
