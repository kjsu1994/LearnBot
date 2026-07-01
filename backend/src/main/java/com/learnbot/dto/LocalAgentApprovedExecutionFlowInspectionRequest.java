package com.learnbot.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record LocalAgentApprovedExecutionFlowInspectionRequest(
        @NotEmpty
        @Size(max = 12)
        List<UUID> requestIds
) {
    public LocalAgentApprovedExecutionFlowInspectionRequest {
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
    }
}
