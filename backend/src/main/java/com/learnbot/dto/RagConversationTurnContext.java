package com.learnbot.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RagConversationTurnContext(
        String question,
        String answer,
        JsonNode evidence
) {
}
