package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record AskRequest(
        @NotBlank String question,
        SearchFilter filter,
        String mode,
        UUID spaceId
) {
}
