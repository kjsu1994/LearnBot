package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocalAgentTokenSummary(
        UUID id,
        UUID agentId,
        String label,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt,
        boolean active
) {
}
