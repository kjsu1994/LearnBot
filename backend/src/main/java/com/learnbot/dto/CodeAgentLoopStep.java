package com.learnbot.dto;

public record CodeAgentLoopStep(
        int index,
        String phase,
        String action,
        AgentExecutionTarget executionTarget,
        LocalAgentToolName toolName,
        boolean requiresApproval,
        boolean mayMutate,
        boolean enabled,
        String stopOnFailure
) {
}
