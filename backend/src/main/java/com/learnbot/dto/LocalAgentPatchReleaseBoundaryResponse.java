package com.learnbot.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LocalAgentPatchReleaseBoundaryResponse(
        UUID requestId,
        String status,
        String actionMode,
        boolean releaseGateEnabled,
        boolean claimEnabled,
        boolean writeHelperEnabled,
        boolean requestCreationEnabled,
        boolean pushEnabled,
        boolean claimable,
        boolean mutationAllowed,
        boolean applyEnabled,
        boolean testEnabled,
        boolean rollbackRestoreEnabled,
        boolean ragFreshnessUpdateEnabled,
        List<String> blockingReasons,
        String message,
        Map<String, Object> patchExecutionGate,
        Map<String, Object> releaseEnablementChecklist,
        LocalAgentPatchReleaseAttemptModel releaseAttemptModel
) {
}
