package com.learnbot.dto.loop;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopFinalResultPublicationPreviewResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String publicationDecision,
        String reason,
        boolean finalResultReady,
        boolean finalResultEnabled,
        boolean publicationEnabled,
        boolean finalAnswerGenerationEnabled,
        boolean finalAnswerDeliveryEnabled,
        boolean acknowledgementEnabled,
        boolean acknowledgementSaveEnabled,
        boolean ragFreshnessUpdateEnabled,
        boolean partialReindexEnabled,
        boolean followUpMutationEnabled,
        boolean mutationEnabled,
        Map<String, Object> handoffSummary,
        Map<String, Object> finalResultHandoff,
        CodeAgentLoopRunnerPreviewResponse runnerPreview
) {
    public CodeAgentLoopFinalResultPublicationPreviewResponse {
        handoffSummary = handoffSummary == null ? Map.of() : Map.copyOf(handoffSummary);
        finalResultHandoff = finalResultHandoff == null ? Map.of() : Map.copyOf(finalResultHandoff);
    }
}
