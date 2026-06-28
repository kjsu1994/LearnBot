package com.learnbot.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LocalAgentPatchReleaseAttempt(
        UUID id,
        UUID sourceRequestId,
        UUID sessionId,
        UUID userId,
        UUID agentId,
        UUID workspaceId,
        String status,
        boolean claimable,
        int staleWindowSeconds,
        Map<String, Object> evidence,
        List<String> failureReasons,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime releasedAt
) {
}
