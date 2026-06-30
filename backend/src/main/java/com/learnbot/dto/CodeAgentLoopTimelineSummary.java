package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CodeAgentLoopTimelineSummary(
        UUID id,
        UUID repositoryId,
        UUID spaceId,
        String instruction,
        String status,
        int maxSteps,
        int timeoutSeconds,
        boolean cancellationEnabled,
        boolean timelinePersistenceEnabled,
        boolean mutationEnabled,
        OffsetDateTime createdAt,
        List<CodeAgentLoopTimelineEventSummary> events
) {
}
