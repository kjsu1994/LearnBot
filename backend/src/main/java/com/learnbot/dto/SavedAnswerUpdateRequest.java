package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record SavedAnswerUpdateRequest(
        @NotBlank String title
) {
}
