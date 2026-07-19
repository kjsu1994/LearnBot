package com.learnbot.service;

import com.learnbot.dto.LocalAgentWorkspaceSummary;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LocalAgentDevice(
        UUID agentId,
        UUID userId,
        UUID installationId,
        String label,
        String clientName,
        String machineName,
        String osName,
        String osVersion,
        String architecture,
        String agentVersion,
        List<String> capabilities,
        List<LocalAgentWorkspaceSummary> workspaces,
        String configuredTransport,
        String activeTransport,
        int webSocketFailureCount,
        OffsetDateTime nextWebSocketRetryAt,
        OffsetDateTime selectedAt,
        OffsetDateTime approvedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime revokedAt,
        OffsetDateTime createdAt
) {
    public LocalAgentDevice {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
    }
}
