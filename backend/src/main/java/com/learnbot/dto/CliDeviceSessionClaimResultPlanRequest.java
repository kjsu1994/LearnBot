package com.learnbot.dto;

public record CliDeviceSessionClaimResultPlanRequest(
        String claimStatus,
        String clientName,
        String cliVersion
) {
}
