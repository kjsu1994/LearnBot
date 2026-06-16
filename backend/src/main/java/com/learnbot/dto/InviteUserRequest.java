package com.learnbot.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record InviteUserRequest(
        @Email @NotBlank String email,
        @NotBlank String displayName,
        @NotBlank String initialPassword,
        String role,
        UUID spaceId,
        String spaceRole
) {
}

