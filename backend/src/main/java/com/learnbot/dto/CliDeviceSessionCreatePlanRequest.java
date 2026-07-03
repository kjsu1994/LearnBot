package com.learnbot.dto;

public record CliDeviceSessionCreatePlanRequest(
        String clientName,
        String cliVersion
) {
}
