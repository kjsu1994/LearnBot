package com.learnbot.dto;

import java.util.List;

public record AskResponse(
        String mode,
        String answer,
        List<SearchResult> citations,
        List<AnswerEvidence> evidence
) {
}
