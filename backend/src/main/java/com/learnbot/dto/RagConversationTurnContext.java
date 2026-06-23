package com.learnbot.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RagConversationTurnContext(
        String question,
        String answer,
        String mode,
        JsonNode evidence
) {
    public RagConversationTurnContext(String question, String answer, JsonNode evidence) {
        this(question, answer, null, evidence);
    }
}
