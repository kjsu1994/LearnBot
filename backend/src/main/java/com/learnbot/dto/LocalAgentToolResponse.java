package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LocalAgentToolResponse(
        UUID sessionId,
        UUID requestId,
        UUID userId,
        UUID agentId,
        UUID workspaceId,
        AgentExecutionTarget executionTarget,
        LocalAgentToolName toolName,
        LocalAgentToolStatus status,
        Map<String, Object> output,
        LocalAgentFailureCode failureCode,
        String error,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<String> warnings
) {
    public LocalAgentToolResponse {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required.");
        }
        if (requestId == null) {
            throw new IllegalArgumentException("requestId is required.");
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
        if (status == null) {
            throw new IllegalArgumentException("status is required.");
        }
        output = copyNonNullOutput(output);
        warnings = warnings == null ? List.of() : warnings.stream()
                .filter(Objects::nonNull)
                .toList();
        startedAt = startedAt == null ? OffsetDateTime.now() : startedAt;
        if (isFailure(status) && failureCode == null) {
            throw new IllegalArgumentException("failureCode is required for failed Local Agent tool responses.");
        }
    }

    private static boolean isFailure(LocalAgentToolStatus status) {
        return switch (status) {
            case FAILED, REJECTED, TIMED_OUT, DISCONNECTED -> true;
            case PENDING, APPROVAL_REQUIRED, APPROVED, APPROVED_HELD, RUNNING, SUCCEEDED, CANCELLED -> false;
        };
    }

    private static Map<String, Object> copyNonNullOutput(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
    }
}
