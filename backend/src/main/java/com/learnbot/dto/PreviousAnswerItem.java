package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record PreviousAnswerItem(
        String label,
        String text,
        List<Integer> citationNumbers,
        List<UUID> evidenceChunkIds
) {
    public PreviousAnswerItem {
        citationNumbers = citationNumbers == null ? List.of() : List.copyOf(citationNumbers);
        evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
    }
}
