package com.learnbot.dto.loop;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CodeAgentLoopRunRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        @NotBlank String instruction,
        Integer maxSteps,
        UUID agentId,
        UUID workspaceId
) {
}
