package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LocalAgentStatusResponse(
        LocalAgentConnectionState state,
        UUID agentId,
        String version,
        OffsetDateTime connectedAt,
        OffsetDateTime lastSeenAt,
        List<String> capabilities,
        List<LocalAgentWorkspaceSummary> workspaces,
        String configuredTransport,
        String activeTransport,
        int webSocketFailureCount,
        OffsetDateTime nextWebSocketRetryAt,
        String message,
        String latestVersion,
        String minimumVersion,
        String updateState,
        String updateUri
) {
    public LocalAgentStatusResponse(
            LocalAgentConnectionState state,
            UUID agentId,
            String version,
            OffsetDateTime connectedAt,
            OffsetDateTime lastSeenAt,
            List<String> capabilities,
            List<LocalAgentWorkspaceSummary> workspaces,
            String configuredTransport,
            String activeTransport,
            int webSocketFailureCount,
            OffsetDateTime nextWebSocketRetryAt,
            String message
    ) {
        this(state, agentId, version, connectedAt, lastSeenAt, capabilities, workspaces,
                configuredTransport, activeTransport, webSocketFailureCount, nextWebSocketRetryAt,
                message, null, null, "UNKNOWN", null);
    }

    public static LocalAgentStatusResponse disconnected() {
        return new LocalAgentStatusResponse(
                LocalAgentConnectionState.DISCONNECTED,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                0,
                null,
                "No Local Agent is connected. User-owned file changes require a per-user Local Agent.",
                null,
                null,
                "UNKNOWN",
                null
        );
    }
}
