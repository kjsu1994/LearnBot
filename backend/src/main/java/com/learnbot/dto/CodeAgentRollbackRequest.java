package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CodeAgentRollbackRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        @NotNull UUID patchSessionId
) {
}
