package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CodeAgentTestRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        @NotNull UUID patchSessionId,
        @NotBlank String commandKey
) {
}
