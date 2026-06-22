package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record RagConversationContext(
        UUID conversationId,
        String rewrittenQuestion,
        List<RagConversationTurnContext> recentTurns
) {
}
