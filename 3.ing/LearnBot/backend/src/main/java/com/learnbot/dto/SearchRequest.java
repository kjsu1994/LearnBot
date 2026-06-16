package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank String query,
        SearchFilter filter,
        Integer limit
) {
}
