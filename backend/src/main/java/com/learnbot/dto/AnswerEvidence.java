package com.learnbot.dto;

import java.util.UUID;

public record AnswerEvidence(
        int citationNumber,
        UUID chunkId,
        UUID documentId,
        String title,
        String sourceUri,
        String sourceType,
        int chunkIndex,
        String preview,
        double score
) {
}
