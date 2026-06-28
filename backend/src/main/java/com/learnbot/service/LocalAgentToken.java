package com.learnbot.service;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocalAgentToken(
        UUID id,
        UUID userId,
        UUID agentId,
        String label,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt
) {
    public boolean activeAt(OffsetDateTime now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
