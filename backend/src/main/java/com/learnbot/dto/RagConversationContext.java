package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record RagConversationContext(
        UUID conversationId,
        String rewrittenQuestion,
        List<RagConversationTurnContext> recentTurns,
        List<CodeConversationAnchor> codeAnchors,
        List<DocumentConversationAnchor> documentAnchors,
        boolean contextual,
        ConversationIntent conversationIntent,
        List<PreviousAnswerItem> previousAnswerItems,
        List<UUID> requiredDocumentChunkIds,
        List<UUID> requiredCodeChunkIds
) {
    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> codeAnchors,
            List<DocumentConversationAnchor> documentAnchors,
            boolean contextual,
            ConversationIntent conversationIntent,
            List<PreviousAnswerItem> previousAnswerItems,
            List<UUID> requiredDocumentChunkIds,
            List<UUID> requiredCodeChunkIds
    ) {
        this.conversationId = conversationId;
        this.rewrittenQuestion = rewrittenQuestion;
        this.recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        this.codeAnchors = codeAnchors == null ? List.of() : List.copyOf(codeAnchors);
        this.documentAnchors = documentAnchors == null ? List.of() : List.copyOf(documentAnchors);
        this.contextual = contextual;
        this.conversationIntent = conversationIntent == null ? ConversationIntent.NONE : conversationIntent;
        this.previousAnswerItems = previousAnswerItems == null ? List.of() : List.copyOf(previousAnswerItems);
        this.requiredDocumentChunkIds = requiredDocumentChunkIds == null ? List.of() : List.copyOf(requiredDocumentChunkIds);
        this.requiredCodeChunkIds = requiredCodeChunkIds == null ? List.of() : List.copyOf(requiredCodeChunkIds);
    }

    public RagConversationContext(
            UUID conversationId,
            String rewrittenQuestion,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> codeAnchors,
            List<DocumentConversationAnchor> documentAnchors,
            boolean contextual
    ) {
        this(conversationId, rewrittenQuestion, recentTurns, codeAnchors, documentAnchors, contextual,
                contextual ? ConversationIntent.REFERENCE_FOLLOWUP : ConversationIntent.NONE, List.of(), List.of(), List.of());
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

    public boolean previousAnswerExpansion() {
        return conversationIntent == ConversationIntent.PREVIOUS_ANSWER_EXPANSION;
    }
}
