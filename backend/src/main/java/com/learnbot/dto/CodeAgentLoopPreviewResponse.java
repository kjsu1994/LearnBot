package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record CodeAgentLoopPreviewResponse(
        UUID loopId,
        UUID repositoryId,
        UUID spaceId,
        String status,
        int maxSteps,
        int timeoutSeconds,
        boolean cancellationEnabled,
        boolean timelinePersistenceEnabled,
        boolean mutationEnabled,
        List<CodeAgentLoopStep> steps,
        List<CodeAgentLoopStopCondition> stopConditions,
        List<String> warnings
) {
}
