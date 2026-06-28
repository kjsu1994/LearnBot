package com.learnbot.dto;

import java.util.UUID;

public record LocalAgentWorkspaceSummary(
        UUID workspaceId,
        String name,
        String rootPath,
        boolean approved
) {
}
