package com.learnbot.dto.loop;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopSideEffectBoundaryResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String boundaryDecision,
        String reason,
        boolean modelToolSelectionAttempted,
        boolean modelProposedSideEffectfulTool,
        boolean approvalRequired,
        boolean releaseRequired,
        boolean releaseGateEnabled,
        boolean requestCreationEnabled,
        boolean enqueueEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean mutationEnabled,
        boolean finalResultEnabled,
        boolean publicationEnabled,
        boolean acknowledgementEnabled,
        CodeAgentLoopRunnerPreviewResponse preview,
        Map<String, Object> modelDecision,
        Map<String, Object> guardrails
) {
}
