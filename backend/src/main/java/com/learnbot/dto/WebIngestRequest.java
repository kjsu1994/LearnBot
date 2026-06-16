package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

public record WebIngestRequest(
        @NotBlank String url
) {
}
