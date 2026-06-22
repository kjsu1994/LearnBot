package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record AskResponse(
        String mode,
        String answer,
        List<SearchResult> citations,
        List<AnswerEvidence> evidence,
        String confidence,
        List<String> diagnostics,
        UUID conversationId,
        UUID turnId,
        String rewrittenQuestion
) {
    public AskResponse(
            String mode,
            String answer,
            List<SearchResult> citations,
            List<AnswerEvidence> evidence
    ) {
        this(mode, answer, citations, evidence, "보통", List.of());
    }

    public AskResponse(
            String mode,
            String answer,
            List<SearchResult> citations,
            List<AnswerEvidence> evidence,
            String confidence,
            List<String> diagnostics
    ) {
        this(mode, answer, citations, evidence, confidence, diagnostics, null, null, null);
    }

    public AskResponse withConversation(UUID conversationId, UUID turnId, String rewrittenQuestion) {
        return new AskResponse(mode, answer, citations, evidence, confidence, diagnostics, conversationId, turnId, rewrittenQuestion);
    }
}
