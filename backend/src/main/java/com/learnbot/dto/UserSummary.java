package com.learnbot.dto;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        String role,
        String status
) {
}

