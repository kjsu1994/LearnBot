package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record SpaceUpdateRequest(
        @NotBlank String name,
        String description
) {
}
