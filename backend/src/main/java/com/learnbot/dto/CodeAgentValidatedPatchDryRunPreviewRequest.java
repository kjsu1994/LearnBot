package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record CodeAgentValidatedPatchDryRunPreviewRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        UUID loopId,
        @NotNull UUID agentId,
        @NotNull UUID workspaceId,
        @NotNull Map<String, Object> validatedHandoff
) {
}
