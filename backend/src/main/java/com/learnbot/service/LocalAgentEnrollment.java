package com.learnbot.service;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocalAgentEnrollment(
        UUID id,
        UUID agentId,
        UUID userId,
        LocalAgentEnrollmentState state,
        String label,
        String clientName,
        String machineName,
        String osName,
        String osVersion,
        String architecture,
        String agentVersion,
        UUID installationId,
        int pollIntervalSeconds,
        int pollViolationCount,
        OffsetDateTime lastPolledAt,
        OffsetDateTime expiresAt,
        OffsetDateTime approvedAt,
        OffsetDateTime deniedAt,
        OffsetDateTime consumedAt,
        UUID candidateTokenId,
        OffsetDateTime candidateExpiresAt,
        OffsetDateTime credentialConfirmBy,
        OffsetDateTime credentialIssuedAt,
        OffsetDateTime createdAt
) {
}
