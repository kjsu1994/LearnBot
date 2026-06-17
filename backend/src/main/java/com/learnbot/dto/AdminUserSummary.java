package com.learnbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record AdminUserSummary(
        UUID id,
        String email,
        String displayName,
        String role,
        String status,
        List<SpaceSummary> spaces
) {
    @JsonProperty("loginId")
    public String loginId() {
        return email;
    }
}
