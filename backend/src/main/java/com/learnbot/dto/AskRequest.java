package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(
        @NotBlank String question,
        SearchFilter filter,
        String mode
) {
}
