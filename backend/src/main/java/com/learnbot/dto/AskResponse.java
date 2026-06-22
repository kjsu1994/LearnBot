package com.learnbot.dto;

import java.util.List;

public record AskResponse(
        String mode,
        String answer,
        List<SearchResult> citations,
        List<AnswerEvidence> evidence,
        String confidence,
        List<String> diagnostics
) {
    public AskResponse(
            String mode,
            String answer,
            List<SearchResult> citations,
            List<AnswerEvidence> evidence
    ) {
        this(mode, answer, citations, evidence, "보통", List.of());
    }
}