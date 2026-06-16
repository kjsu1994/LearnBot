package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record SpaceCreateRequest(
        @NotBlank String name,
        String description
) {
}

