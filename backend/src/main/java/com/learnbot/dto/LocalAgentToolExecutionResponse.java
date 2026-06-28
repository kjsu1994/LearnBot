package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LocalAgentToolExecutionResponse(
        UUID requestId,
        UUID sessionId,
        UUID userId,
        UUID agentId,
        UUID workspaceId,
        AgentExecutionTarget executionTarget,
        LocalAgentToolName toolName,
        LocalAgentApprovalState approvalState,
        LocalAgentToolStatus status,
        Map<String, Object> input,
        Map<String, Object> output,
        LocalAgentFailureCode failureCode,
        String error,
        List<String> requestWarnings,
        List<String> responseWarnings,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
}
