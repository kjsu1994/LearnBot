package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CodeAgentLoopTimelineEventSummary(
        UUID id,
        int sequenceNumber,
        String eventType,
        String phase,
        AgentExecutionTarget executionTarget,
        LocalAgentToolName toolName,
        boolean requiresApproval,
        boolean mayMutate,
        boolean enabled,
        Map<String, Object> details,
        OffsetDateTime createdAt
) {
}
