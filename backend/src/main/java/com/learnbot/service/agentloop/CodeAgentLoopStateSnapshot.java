package com.learnbot.service.agentloop;

import java.util.List;
import java.util.Map;

public record CodeAgentLoopStateSnapshot(
        CodeAgentLoopState state,
        CodeAgentLoopTransition lastTransition,
        List<CodeAgentLoopTransition> availableTransitions,
        String blockedReason,
        String sourceEventType,
        Integer sourceSequenceNumber
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "schema", "learnbot.server.code-agent.loop-state-snapshot.v1",
                "state", state == null ? CodeAgentLoopState.CREATED.name() : state.name(),
                "lastTransition", transitionMap(lastTransition),
                "availableTransitions", availableTransitions == null
                        ? List.of()
                        : availableTransitions.stream().map(CodeAgentLoopStateSnapshot::transitionMap).toList(),
                "blockedReason", blockedReason == null ? "" : blockedReason,
                "sourceEventType", sourceEventType == null ? "" : sourceEventType,
                "sourceSequenceNumber", sourceSequenceNumber == null ? -1 : sourceSequenceNumber
        );
    }

    private static Map<String, Object> transitionMap(CodeAgentLoopTransition transition) {
        if (transition == null) {
            return Map.of();
        }
        return Map.of(
                "from", transition.from().name(),
                "event", transition.event().name(),
                "to", transition.to().name(),
                "sideEffectAllowed", transition.sideEffectAllowed(),
                "approvalRequired", transition.approvalRequired(),
                "reason", transition.reason()
        );
    }
}
