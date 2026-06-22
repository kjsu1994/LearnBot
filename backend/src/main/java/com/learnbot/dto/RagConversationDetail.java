package com.learnbot.dto;

import java.util.List;

public record RagConversationDetail(
        RagConversationSummary conversation,
        List<RagConversationTurn> turns
) {
}
