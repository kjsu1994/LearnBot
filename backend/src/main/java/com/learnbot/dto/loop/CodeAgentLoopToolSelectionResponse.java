package com.learnbot.dto.loop;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopToolSelectionResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String selectionDecision,
        String reason,
        boolean modelToolSelectionAttempted,
        boolean modelToolSelectionAccepted,
        boolean selectedByModel,
        boolean requestCreationEnabled,
        boolean enqueueEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean mutationEnabled,
        CodeAgentLoopRunnerPreviewResponse preview,
        CodeAgentLoopToolCandidate candidate,
        Map<String, Object> modelDecision,
        Map<String, Object> guardrails
) {
}
