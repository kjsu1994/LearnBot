package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

public record AdminSettingsUpdateRequest(
        @NotNull Boolean respectRobotsTxt
) {
}
