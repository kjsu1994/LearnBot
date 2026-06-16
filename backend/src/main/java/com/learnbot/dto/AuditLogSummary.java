package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditLogSummary(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        UUID spaceId,
        String spaceName,
        String message,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {
}

