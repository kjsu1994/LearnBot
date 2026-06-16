package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        String loginId,
        String email,
        @NotBlank String password
) {
    public String identifier() {
        if (loginId != null && !loginId.isBlank()) {
            return loginId;
        }
        return email;
    }
}
