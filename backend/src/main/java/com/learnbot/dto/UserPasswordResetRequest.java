package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record UserPasswordResetRequest(
        @NotBlank String newPassword
) {
}
