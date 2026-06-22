package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RagConversationSummary(
        UUID id,
        UUID spaceId,
        String domain,
        UUID repositoryId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
