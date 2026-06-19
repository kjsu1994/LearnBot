package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SavedAnswerSummary(
        UUID id,
        UUID spaceId,
        String answerType,
        String question,
        String mode,
        String answerPreview,
        String confidence,
        UUID repositoryId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
