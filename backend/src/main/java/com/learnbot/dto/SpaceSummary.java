package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SpaceSummary(
        UUID id,
        String name,
        String description,
        String role,
        OffsetDateTime createdAt
) {
}

