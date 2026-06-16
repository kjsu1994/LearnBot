package com.learnbot.dto;

import java.util.List;

public record AskResponse(
        String answer,
        List<SearchResult> citations
) {
}
