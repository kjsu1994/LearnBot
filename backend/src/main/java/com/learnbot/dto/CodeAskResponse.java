package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record CodeAskResponse(
        String mode,
        String answer,
        List<CodeEvidence> evidence,
        String confidence,
        List<String> diagnostics,
        UUID conversationId,
        UUID turnId,
        String rewrittenQuestion
) {
    public CodeAskResponse(
            String mode,
            String answer,
            List<CodeEvidence> evidence,
            String confidence,
            List<String> diagnostics
    ) {
        this(mode, answer, evidence, confidence, diagnostics, null, null, null);
    }

    public CodeAskResponse withConversation(UUID conversationId, UUID turnId, String rewrittenQuestion) {
        return new CodeAskResponse(mode, answer, evidence, confidence, diagnostics, conversationId, turnId, rewrittenQuestion);
    }
}
