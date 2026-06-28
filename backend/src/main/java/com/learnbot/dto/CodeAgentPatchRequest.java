package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CodeAgentPatchRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        @NotBlank String instruction,
        List<String> targetFiles
) {
}
