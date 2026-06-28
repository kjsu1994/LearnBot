package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentFailureCode;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LocalAgentToolExecution(
        UUID id,
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
