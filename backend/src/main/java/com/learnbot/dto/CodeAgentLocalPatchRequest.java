package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CodeAgentLocalPatchRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        @NotNull UUID agentId,
        @NotNull UUID workspaceId,
        @NotBlank String instruction,
        @NotBlank String diff,
        List<String> targetFiles
) {
}
