package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record RagConversationContext(
        UUID conversationId,
        String rewrittenQuestion,
        List<RagConversationTurnContext> recentTurns,
        List<CodeConversationAnchor> codeAnchors,
        boolean contextual
) {
    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> codeAnchors
    ) {
        this(conversationId, rewrittenQuestion, recentTurns, codeAnchors, false);
    }

    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns
    ) {
        this(conversationId, rewrittenQuestion, recentTurns, List.of(), false);
    }
}
