package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CodeSearchRequest(
        UUID repositoryId,
        UUID spaceId,
        @NotBlank String query,
        Integer limit
) {
}
