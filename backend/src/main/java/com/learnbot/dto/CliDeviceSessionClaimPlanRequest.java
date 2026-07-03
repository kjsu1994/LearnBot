package com.learnbot.dto;

public record CliDeviceSessionClaimPlanRequest(
        String deviceCode,
        String clientName,
        String cliVersion
) {
}
