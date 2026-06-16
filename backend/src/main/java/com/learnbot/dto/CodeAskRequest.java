package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CodeAskRequest(
        UUID repositoryId,
        UUID spaceId,
        @NotBlank String question,
        String mode,
        Integer limit
) {
}
