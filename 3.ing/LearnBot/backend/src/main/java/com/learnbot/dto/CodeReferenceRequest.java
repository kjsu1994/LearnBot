package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CodeReferenceRequest(
        UUID repositoryId,
        @NotBlank String symbol,
        Integer limit
) {
}
