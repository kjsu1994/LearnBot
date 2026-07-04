package com.learnbot.service.agentloop;

public record CodeAgentLoopTransition(
        CodeAgentLoopState from,
        CodeAgentLoopEvent event,
        CodeAgentLoopState to,
        boolean sideEffectAllowed,
        boolean approvalRequired,
        String reason
) {
}
