package com.learnbot.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SavedAnswerRequest(
        @NotNull UUID spaceId,
        @NotBlank String answerType,
        @NotBlank String question,
        String mode,
        @NotBlank String answer,
        JsonNode citations,
        JsonNode evidence,
        String confidence,
        JsonNode diagnostics,
        UUID repositoryId,
        String title
) {
}
