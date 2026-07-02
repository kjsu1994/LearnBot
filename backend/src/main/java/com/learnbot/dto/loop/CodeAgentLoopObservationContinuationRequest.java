package com.learnbot.dto.loop;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CodeAgentLoopObservationContinuationRequest(
        @NotNull UUID repositoryId,
        UUID loopId,
        UUID agentId,
        UUID workspaceId,
        @NotNull UUID requestId
) {
}
