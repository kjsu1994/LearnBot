package com.learnbot.dto.loop;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopApprovalRequestPreviewResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String approvalDecision,
        String reason,
        boolean approvalRequestPrepared,
        boolean approvalRequired,
        boolean releaseRequired,
        boolean releaseEvidenceAvailable,
        boolean releaseGateEnabled,
        boolean requestCreationEnabled,
        boolean enqueueEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean mutationEnabled,
        boolean finalResultEnabled,
        boolean publicationEnabled,
        boolean acknowledgementEnabled,
        CodeAgentLoopSideEffectBoundaryResponse boundary,
        CodeAgentLoopToolCandidate candidate,
        Map<String, Object> guardrails
) {
}
