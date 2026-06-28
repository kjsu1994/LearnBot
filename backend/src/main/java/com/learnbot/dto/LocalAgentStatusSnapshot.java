package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LocalAgentStatusSnapshot(
        UUID agentId,
        UUID userId,
        String version,
        OffsetDateTime connectedAt,
        OffsetDateTime lastSeenAt,
        List<String> capabilities,
        List<LocalAgentWorkspaceSummary> workspaces
) {
    public LocalAgentStatusSnapshot {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        connectedAt = connectedAt == null ? now : connectedAt;
        lastSeenAt = lastSeenAt == null ? connectedAt : lastSeenAt;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
    }
}
