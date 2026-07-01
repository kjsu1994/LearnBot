package com.learnbot.dto.loop;

import com.learnbot.dto.LocalAgentToolExecutionResponse;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopPatchApprovalRequestResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String approvalDecision,
        String reason,
        boolean approvalRequestCreated,
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
        CodeAgentLoopApprovalRequestPreviewResponse preview,
        LocalAgentToolExecutionResponse approvalRequest,
        Map<String, Object> guardrails
) {
}
