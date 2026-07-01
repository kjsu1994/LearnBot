package com.learnbot.dto.loop;

import com.learnbot.dto.LocalAgentQueuedToolRequest;

import java.util.UUID;

public record CodeAgentLoopSelectedToolEnqueueResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String runnerDecision,
        String reason,
        boolean modelToolSelectionAttempted,
        boolean modelToolSelectionAccepted,
        boolean selectedByModel,
        boolean requestCreationEnabled,
        boolean enqueueEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean mutationEnabled,
        boolean finalResultEnabled,
        boolean publicationEnabled,
        boolean acknowledgementEnabled,
        CodeAgentLoopToolSelectionResponse selection,
        LocalAgentQueuedToolRequest queuedRequest
) {
}
