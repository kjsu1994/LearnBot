package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CodeTurnChangeAssistRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        @NotBlank String instruction
) {
}
