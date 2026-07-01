package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LocalAgentApprovedExecutionFlowReleaseAttemptInspectionRequest(
        @NotNull
        UUID releaseAttemptId
) {
}
