package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

public record LocalAgentApprovalDecisionRequest(
        @NotNull LocalAgentApprovalDecision decision
) {
}
