package com.learnbot.dto.loop;

import com.learnbot.dto.CodeAgentLoopNextActionResponse;

import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopRunnerPreviewResponse(
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
        CodeAgentLoopNextActionResponse nextAction,
        CodeAgentLoopToolCandidate candidate,
        Map<String, Object> guardrails,
        Map<String, Object> recommendedAction
) {
    public CodeAgentLoopRunnerPreviewResponse(
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
            CodeAgentLoopNextActionResponse nextAction,
            CodeAgentLoopToolCandidate candidate,
            Map<String, Object> guardrails
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
                nextAction,
                candidate,
                guardrails,
                Map.of()
        );
    }

    public CodeAgentLoopRunnerPreviewResponse(
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
            Map<String, ?> handoffSummary,
            CodeAgentLoopNextActionResponse nextAction,
            CodeAgentLoopToolCandidate candidate,
            Map<String, ?> guardrails
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
                copyObjectMap(handoffSummary),
                nextAction,
                candidate,
                copyObjectMap(guardrails),
                Map.of()
        );
    }

    private static Map<String, Object> copyObjectMap(Map<String, ?> source) {
        return source == null ? Map.of() : Map.copyOf(source);
    }
}
