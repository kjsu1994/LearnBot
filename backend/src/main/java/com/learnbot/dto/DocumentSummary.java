package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentSummary(
        UUID id,
        UUID sourceId,
        UUID spaceId,
        String sourceType,
        String sourceStatus,
        String title,
        String sourceUri,
        String contentType,
        OffsetDateTime createdAt
) {
}
