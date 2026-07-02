package com.learnbot.dto.loop;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopM8EntryReadinessResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String m7ClosureDecision,
        String m8EntryDecision,
        String reason,
        boolean m7ClosureReady,
        boolean m8EntryReady,
        boolean finalResultHandoffReady,
        boolean finalResultPublicationPreviewReady,
        boolean m8WorkEnabled,
        boolean cliPackagingEnabled,
        boolean installerEnabled,
        boolean publicationEnabled,
        boolean finalAnswerDeliveryEnabled,
        boolean acknowledgementSaveEnabled,
        boolean ragFreshnessUpdateEnabled,
        boolean mutationEnabled,
        List<String> blockingReasons,
        Map<String, Object> handoffSummary,
        Map<String, Object> finalResultHandoff,
        CodeAgentLoopFinalResultPublicationPreviewResponse finalResultPublicationPreview
) {
    public CodeAgentLoopM8EntryReadinessResponse {
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        handoffSummary = handoffSummary == null ? Map.of() : Map.copyOf(handoffSummary);
        finalResultHandoff = finalResultHandoff == null ? Map.of() : Map.copyOf(finalResultHandoff);
    }
}
