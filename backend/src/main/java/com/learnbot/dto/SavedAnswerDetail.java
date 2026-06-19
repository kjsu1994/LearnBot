package com.learnbot.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SavedAnswerDetail(
        UUID id,
        UUID spaceId,
        String answerType,
        String question,
        String mode,
        String answer,
        JsonNode citations,
        JsonNode evidence,
        String confidence,
        JsonNode diagnostics,
        UUID repositoryId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
