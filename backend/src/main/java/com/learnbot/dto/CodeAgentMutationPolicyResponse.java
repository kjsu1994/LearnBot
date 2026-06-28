package com.learnbot.dto;

import java.util.List;

public record CodeAgentMutationPolicyResponse(
        AgentExecutionTarget intendedExecutionTarget,
        boolean localAgentMutationEnabled,
        boolean serverLocalMutationEnabled,
        List<LocalAgentToolName> futureLocalAgentTools,
        List<String> warnings,
        String message
) {
}
