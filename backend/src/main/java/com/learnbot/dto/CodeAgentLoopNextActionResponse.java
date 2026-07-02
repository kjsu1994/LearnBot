package com.learnbot.dto;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopNextActionResponse(
        UUID loopId,
        UUID repositoryId,
        String status,
        String actionKey,
        String reason,
        boolean requestCreationEnabled,
        boolean pushEnabled,
        boolean claimEnabled,
        boolean mutationEnabled,
        boolean finalResultEnabled,
        boolean publicationEnabled,
        boolean acknowledgementEnabled,
        UUID sourceEventId,
        Integer sourceSequenceNumber,
        String sourceEventType,
        Map<String, Object> handoffSummary,
        Map<String, Object> sourceDetails,
        Map<String, Object> recommendedAction
) {
    public CodeAgentLoopNextActionResponse(
            UUID loopId,
            UUID repositoryId,
            String status,
            String actionKey,
            String reason,
            boolean requestCreationEnabled,
            boolean pushEnabled,
            boolean claimEnabled,
            boolean mutationEnabled,
            boolean finalResultEnabled,
            boolean publicationEnabled,
            boolean acknowledgementEnabled,
            UUID sourceEventId,
            Integer sourceSequenceNumber,
            String sourceEventType,
            Map<String, Object> sourceDetails
    ) {
        this(
                loopId,
                repositoryId,
                status,
                actionKey,
                reason,
                requestCreationEnabled,
                pushEnabled,
                claimEnabled,
                mutationEnabled,
                finalResultEnabled,
                publicationEnabled,
                acknowledgementEnabled,
                sourceEventId,
                sourceSequenceNumber,
                sourceEventType,
                Map.of(),
                sourceDetails,
                Map.of()
        );
    }

    public CodeAgentLoopNextActionResponse(
            UUID loopId,
            UUID repositoryId,
            String status,
            String actionKey,
            String reason,
            boolean requestCreationEnabled,
            boolean pushEnabled,
            boolean claimEnabled,
            boolean mutationEnabled,
            boolean finalResultEnabled,
            boolean publicationEnabled,
            boolean acknowledgementEnabled,
            UUID sourceEventId,
            Integer sourceSequenceNumber,
            String sourceEventType,
            Map<String, ?> handoffSummary,
            Map<String, ?> sourceDetails
    ) {
        this(
                loopId,
                repositoryId,
                status,
                actionKey,
                reason,
                requestCreationEnabled,
                pushEnabled,
                claimEnabled,
                mutationEnabled,
                finalResultEnabled,
                publicationEnabled,
                acknowledgementEnabled,
                sourceEventId,
                sourceSequenceNumber,
                sourceEventType,
                copyObjectMap(handoffSummary),
                copyObjectMap(sourceDetails),
                Map.of()
        );
    }

    private static Map<String, Object> copyObjectMap(Map<String, ?> source) {
        return source == null ? Map.of() : Map.copyOf(source);
    }
}
