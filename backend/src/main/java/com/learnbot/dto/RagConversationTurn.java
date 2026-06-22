package com.learnbot.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RagConversationTurn(
        UUID id,
        UUID conversationId,
        UUID parentTurnId,
        String question,
        String rewrittenQuestion,
        String mode,
        String answer,
        String confidence,
        JsonNode citations,
        JsonNode evidence,
        JsonNode diagnostics,
        JsonNode metadata,
        OffsetDateTime createdAt
) {
}
