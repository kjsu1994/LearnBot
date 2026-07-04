package com.learnbot.dto.loop;

import com.learnbot.dto.CodeAgentLoopNextActionResponse;
import com.learnbot.dto.CodeAgentLoopTimelineSummary;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopRunStatusResponse(
        String schema,
        UUID loopId,
        UUID repositoryId,
        UUID agentId,
        UUID workspaceId,
        String status,
        String actionKey,
        String runnerDecision,
        String reason,
        boolean advanceAvailable,
        boolean waitingForLocalAgent,
        boolean mutationEnabled,
        boolean requiresApprovalBeforeMutation,
        CodeAgentLoopNextActionResponse nextAction,
        CodeAgentLoopRunnerEnqueueResponse advance,
        CodeAgentLoopTimelineSummary timeline,
        Map<String, Object> finalReport,
        List<String> warnings,
        String loopState,
        Map<String, Object> stateSnapshot
) {
    public CodeAgentLoopRunStatusResponse(
            String schema,
            UUID loopId,
            UUID repositoryId,
            UUID agentId,
            UUID workspaceId,
            String status,
            String actionKey,
            String runnerDecision,
            String reason,
            boolean advanceAvailable,
            boolean waitingForLocalAgent,
            boolean mutationEnabled,
            boolean requiresApprovalBeforeMutation,
            CodeAgentLoopNextActionResponse nextAction,
            CodeAgentLoopRunnerEnqueueResponse advance,
            CodeAgentLoopTimelineSummary timeline,
            Map<String, Object> finalReport,
            List<String> warnings
    ) {
        this(
                schema,
                loopId,
                repositoryId,
                agentId,
                workspaceId,
                status,
                actionKey,
                runnerDecision,
                reason,
                advanceAvailable,
                waitingForLocalAgent,
                mutationEnabled,
                requiresApprovalBeforeMutation,
                nextAction,
                advance,
                timeline,
                finalReport,
                warnings,
                nextAction == null ? null : nextAction.loopState(),
                nextAction == null ? Map.of() : nextAction.stateSnapshot()
        );
    }
}
