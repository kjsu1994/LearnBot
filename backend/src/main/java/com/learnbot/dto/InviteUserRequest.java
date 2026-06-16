package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record InviteUserRequest(
        String loginId,
        String email,
        @NotBlank String displayName,
        @NotBlank String initialPassword,
        String role,
        UUID spaceId,
        String spaceRole
) {
    public String identifier() {
        if (loginId != null && !loginId.isBlank()) {
            return loginId;
        }
        return email;
    }
}
