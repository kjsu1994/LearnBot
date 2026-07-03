package com.learnbot.dto.loop;

import com.learnbot.dto.SavedAnswerDetail;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopFinalResultPublicationResponse(
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
        UUID savedAnswerId,
        String finalAnswer,
        String staleIndexDisclosure,
        Map<String, Object> handoffSummary,
        Map<String, Object> finalResultHandoff,
        SavedAnswerDetail savedAnswer,
        CodeAgentLoopFinalResultPublicationPreviewResponse preview
) {
    public CodeAgentLoopFinalResultPublicationResponse {
        handoffSummary = handoffSummary == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(handoffSummary));
        finalResultHandoff = finalResultHandoff == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(finalResultHandoff));
    }
}
