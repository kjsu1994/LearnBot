package com.learnbot.dto.loop;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CodeAgentLoopPatchApprovalPayloadRequest(
        @NotNull UUID repositoryId,
        UUID spaceId,
        UUID loopId,
        @NotNull UUID agentId,
        @NotNull UUID workspaceId,
        @NotBlank String instruction,
        @NotBlank String diff,
        List<String> targetFiles
) {
}
