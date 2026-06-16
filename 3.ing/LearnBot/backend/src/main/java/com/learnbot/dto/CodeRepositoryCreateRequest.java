package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CodeRepositoryCreateRequest(
        @NotBlank String gitUrl,
        String name,
        String branch,
        @Pattern(regexp = "NONE|TOKEN", message = "authType must be NONE or TOKEN.")
        String authType,
        String username,
        String token,
        Boolean storeToken
) {
}
