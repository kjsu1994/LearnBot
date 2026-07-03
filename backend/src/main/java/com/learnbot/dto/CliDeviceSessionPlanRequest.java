package com.learnbot.dto;

public record CliDeviceSessionPlanRequest(
        String clientName,
        String cliVersion
) {
}
