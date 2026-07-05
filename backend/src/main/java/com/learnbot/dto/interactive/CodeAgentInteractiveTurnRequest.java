package com.learnbot.dto.interactive;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CodeAgentInteractiveTurnRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        UUID conversationId,
        UUID parentTurnId,
        @NotBlank String message,
        String intentHint,
        Integer maxSteps,
        UUID agentId,
        UUID workspaceId
) {
}
