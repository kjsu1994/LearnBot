package com.learnbot.service;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocalAgentCredentialRotation(
        UUID id,
        UUID userId,
        UUID agentId,
        UUID currentTokenId,
        UUID candidateTokenId,
        OffsetDateTime candidateExpiresAt,
        OffsetDateTime confirmBy,
        OffsetDateTime confirmedAt,
        OffsetDateTime cancelledAt,
        OffsetDateTime createdAt
) {
    public boolean pendingAt(OffsetDateTime now) {
        return confirmedAt == null && cancelledAt == null && confirmBy.isAfter(now);
    }
}
