package com.learnbot.service.coderag.model;

import com.learnbot.dto.CodeSearchResult;

import java.util.List;
import java.util.Objects;

public record CodeEvidenceExtractionContext(
        String question,
        EvidenceExtractionStage stage,
        List<CodeSearchResult> evidence,
        int maxItemsPerExtractor
) {
    public static final int DEFAULT_MAX_ITEMS = 64;
    public static final int MAX_ITEMS = 256;

    public CodeEvidenceExtractionContext {
        question = question == null ? "" : question.trim();
        stage = Objects.requireNonNull(stage, "stage");
        evidence = evidence == null ? List.of() : evidence.stream().filter(Objects::nonNull).toList();
        maxItemsPerExtractor = Math.max(1, Math.min(MAX_ITEMS, maxItemsPerExtractor));
    }

    public CodeEvidenceExtractionContext(
            String question,
            EvidenceExtractionStage stage,
            List<CodeSearchResult> evidence
    ) {
        this(question, stage, evidence, DEFAULT_MAX_ITEMS);
    }
}
