package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocalAgentPairingTokenResponse(
        UUID tokenId,
        UUID agentId,
        String token,
        OffsetDateTime expiresAt
) {
}
