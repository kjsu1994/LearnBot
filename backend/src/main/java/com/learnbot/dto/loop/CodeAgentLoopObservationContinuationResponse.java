package com.learnbot.dto.loop;

import com.learnbot.dto.LocalAgentToolExecutionResponse;

import java.util.UUID;

public record CodeAgentLoopObservationContinuationResponse(
        UUID loopId,
        UUID repositoryId,
        UUID requestId,
        String status,
        String continuationDecision,
        String reason,
        boolean requestCreationEnabled,
        boolean enqueueEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean finalResultEnabled,
        boolean publicationEnabled,
        boolean acknowledgementEnabled,
        boolean mutationEnabled,
        int iterationCount,
        int maxIterations,
        int remainingIterations,
        boolean iterationLimitReached,
        LocalAgentToolExecutionResponse observation,
        CodeAgentLoopRunnerPreviewResponse runnerPreview,
        CodeAgentLoopToolSelectionResponse toolSelectionPreview
) {
}
