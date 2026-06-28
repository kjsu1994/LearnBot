package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LocalAgentToolRequest(
        UUID sessionId,
        UUID userId,
        UUID agentId,
        UUID workspaceId,
        AgentExecutionTarget executionTarget,
        LocalAgentToolName toolName,
        Map<String, Object> input,
        LocalAgentApprovalState approvalState,
        OffsetDateTime createdAt,
        List<String> warnings
) {
    public LocalAgentToolRequest {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required.");
        }
        if (executionTarget == null) {
            throw new IllegalArgumentException("executionTarget is required.");
        }
        if (toolName == null) {
            throw new IllegalArgumentException("toolName is required.");
        }
        if (executionTarget == AgentExecutionTarget.USER_LOCAL_AGENT && agentId == null) {
            throw new IllegalArgumentException("agentId is required for USER_LOCAL_AGENT requests.");
        }
        if (requiresWorkspace(toolName) && workspaceId == null) {
            throw new IllegalArgumentException("workspaceId is required for workspace-scoped Local Agent tools.");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        createdAt = createdAt == null ? OffsetDateTime.now() : createdAt;
        approvalState = approvalState == null
                ? (toolName.isSideEffectful() ? LocalAgentApprovalState.REQUIRED : LocalAgentApprovalState.NOT_REQUIRED)
                : approvalState;
        if (toolName.isSideEffectful() && approvalState == LocalAgentApprovalState.NOT_REQUIRED) {
            throw new IllegalArgumentException("Side-effectful Local Agent tools require approval metadata.");
        }
    }

    private static boolean requiresWorkspace(LocalAgentToolName toolName) {
        return switch (toolName) {
            case FILE_READ, PATCH_APPLY, GIT_STATUS, GIT_DIFF, COMMAND_RUN_ALLOWED, ROLLBACK_RESTORE -> true;
            case AGENT_STATUS, AGENT_DOCTOR, WORKSPACE_LIST, WORKSPACE_ADD -> false;
        };
    }
}
