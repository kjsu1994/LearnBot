package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record LocalAgentPatchExecutionReadinessResponse(
        UUID requestId,
        boolean readyToRelease,
        List<LocalAgentPatchExecutionReadinessCheck> checks,
        List<String> warnings,
        String message
) {
}
