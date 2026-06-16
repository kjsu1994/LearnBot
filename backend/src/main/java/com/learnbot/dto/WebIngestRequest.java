package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record WebIngestRequest(
        @NotBlank String url,
        UUID spaceId
) {
}
