package com.learnbot.dto;

import java.util.List;

public record CodeReferenceResponse(
        String symbol,
        List<CodeSearchResult> definitions,
        List<CodeSearchResult> references
) {
}
