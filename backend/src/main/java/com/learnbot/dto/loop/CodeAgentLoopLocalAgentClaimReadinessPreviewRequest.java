package com.learnbot.dto.loop;

import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopLocalAgentClaimReadinessPreviewRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        UUID agentId,
        UUID workspaceId,
        Map<String, Object> patchDryRunLocalAgentQueuePreview
) {
}
