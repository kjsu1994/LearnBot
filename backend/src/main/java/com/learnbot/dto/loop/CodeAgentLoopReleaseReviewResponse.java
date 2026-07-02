package com.learnbot.dto.loop;

import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopReleaseReviewResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String runnerDecision,
        String reason,
        boolean requestCreationEnabled,
        boolean enqueueEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean mutationEnabled,
        boolean finalResultEnabled,
        boolean publicationEnabled,
        boolean acknowledgementEnabled,
        Map<String, Object> handoffSummary,
        CodeAgentLoopRunnerPreviewResponse preview,
        LocalAgentPatchReleaseBoundaryResponse boundary
) {
}
