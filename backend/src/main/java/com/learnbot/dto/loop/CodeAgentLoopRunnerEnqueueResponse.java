package com.learnbot.dto.loop;

import com.learnbot.dto.LocalAgentQueuedToolRequest;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopRunnerEnqueueResponse(
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
        LocalAgentQueuedToolRequest queuedRequest
) {
    public CodeAgentLoopRunnerEnqueueResponse(
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
            CodeAgentLoopRunnerPreviewResponse preview,
            LocalAgentQueuedToolRequest queuedRequest
    ) {
        this(
                loopId,
                repositoryId,
                status,
                actionKey,
                runnerDecision,
                reason,
                requestCreationEnabled,
                enqueueEnabled,
                pushEnabled,
                claimEnabled,
                mutationEnabled,
                finalResultEnabled,
                publicationEnabled,
                acknowledgementEnabled,
                Map.of(),
                preview,
                queuedRequest
        );
    }
}
