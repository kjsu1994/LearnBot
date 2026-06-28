package com.learnbot.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LocalAgentPatchExecutionReadinessResponse(
        UUID requestId,
        boolean readyToRelease,
        List<LocalAgentPatchExecutionReadinessCheck> checks,
        List<String> warnings,
        String message,
        Map<String, Object> patchReleaseReadiness,
        Map<String, Object> patchExecutionGate,
        LocalAgentPatchReleaseAttemptModel releaseAttemptModel,
        Map<String, Object> snapshotReadiness,
        Map<String, Object> rollbackReadiness,
        Map<String, Object> repositoryVerification,
        Map<String, Object> workspaceVerification
) {
}
