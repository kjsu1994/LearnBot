package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record LocalAgentHeartbeatRequest(
        @NotNull UUID agentId,
        String version,
        List<LocalAgentToolName> capabilities,
        List<LocalAgentWorkspaceSummary> workspaces
) {
}
