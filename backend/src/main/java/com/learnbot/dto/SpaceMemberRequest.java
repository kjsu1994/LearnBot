package com.learnbot.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SpaceMemberRequest(
        @NotNull UUID userId,
        String role
) {
}

