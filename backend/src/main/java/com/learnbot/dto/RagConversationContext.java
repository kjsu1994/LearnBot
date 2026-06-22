package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record RagConversationContext(
        UUID conversationId,
        String rewrittenQuestion,
        List<RagConversationTurnContext> recentTurns,
        List<CodeConversationAnchor> codeAnchors,
        List<DocumentConversationAnchor> documentAnchors,
        boolean contextual
) {
    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> codeAnchors,
            List<DocumentConversationAnchor> documentAnchors,
            boolean contextual
    ) {
        this.conversationId = conversationId;
        this.rewrittenQuestion = rewrittenQuestion;
        this.recentTurns = recentTurns == null ? List.of() : recentTurns;
        this.codeAnchors = codeAnchors == null ? List.of() : codeAnchors;
        this.documentAnchors = documentAnchors == null ? List.of() : documentAnchors;
        this.contextual = contextual;
    }

    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> codeAnchors,
            List<DocumentConversationAnchor> documentAnchors
    ) {
        this(conversationId, rewrittenQuestion, recentTurns, codeAnchors, documentAnchors, false);
    }

    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> codeAnchors
    ) {
        this(conversationId, rewrittenQuestion, recentTurns, codeAnchors, List.of(), false);
    }

    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> codeAnchors,
            boolean contextual
    ) {
        this(conversationId, rewrittenQuestion, recentTurns, codeAnchors, List.of(), contextual);
    }

    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns
    ) {
        this(conversationId, rewrittenQuestion, recentTurns, List.of(), List.of(), false);
    }
}
