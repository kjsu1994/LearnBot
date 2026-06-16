package com.learnbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        String role,
        String status
) {
    @JsonProperty("loginId")
    public String loginId() {
        return email;
    }
}
