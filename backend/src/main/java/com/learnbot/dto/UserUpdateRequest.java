package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record UserUpdateRequest(
        String loginId,
        @NotBlank String displayName,
        String role
) {
}
