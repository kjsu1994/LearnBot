package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TrashItemSummary(
        String type,
        UUID id,
        UUID spaceId,
        String title,
        String subtitle,
        String status,
        OffsetDateTime deletedAt,
        OffsetDateTime expiresAt,
        boolean restorable,
        String message
) {
}
