package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record SearchRequest(
        @NotBlank String query,
        SearchFilter filter,
        Integer limit,
        UUID spaceId
) {
}
