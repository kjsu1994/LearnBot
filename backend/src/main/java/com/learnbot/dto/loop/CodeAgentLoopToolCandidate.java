package com.learnbot.dto.loop;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopToolCandidate(
        UUID sessionId,
        UUID userId,
        UUID agentId,
        UUID workspaceId,
        AgentExecutionTarget executionTarget,
        LocalAgentToolName toolName,
        LocalAgentApprovalState approvalState,
        boolean sideEffectful,
        boolean requiresApproval,
        boolean enqueueEnabled,
        boolean mutationAllowed,
        Map<String, Object> input,
        List<String> warnings
) {
}
