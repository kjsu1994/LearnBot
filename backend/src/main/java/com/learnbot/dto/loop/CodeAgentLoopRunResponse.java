package com.learnbot.dto.loop;

import com.learnbot.dto.LocalAgentQueuedToolRequest;

import java.util.List;
import java.util.UUID;

public record CodeAgentLoopRunResponse(
        String schema,
        UUID loopId,
        UUID repositoryId,
        UUID spaceId,
        UUID agentId,
        UUID workspaceId,
        String instruction,
        int maxSteps,
        String status,
        boolean created,
        boolean readOnlyEnqueueAttempted,
        boolean readOnlyQueued,
        boolean mutationEnabled,
        boolean requiresApprovalBeforeMutation,
        String nextAction,
        LocalAgentQueuedToolRequest queuedRequest,
        List<String> warnings
) {
}
