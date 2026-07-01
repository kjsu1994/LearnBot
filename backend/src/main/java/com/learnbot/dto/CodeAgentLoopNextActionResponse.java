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
        Map<String, Object> sourceDetails
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
                sourceDetails
        );
    }
}
