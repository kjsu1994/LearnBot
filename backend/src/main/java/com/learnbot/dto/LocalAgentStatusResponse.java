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
        String message
) {
    public static LocalAgentStatusResponse disconnected() {
        return new LocalAgentStatusResponse(
                LocalAgentConnectionState.DISCONNECTED,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                "No Local Agent is connected. User-owned file changes require a per-user Local Agent."
        );
    }
}
