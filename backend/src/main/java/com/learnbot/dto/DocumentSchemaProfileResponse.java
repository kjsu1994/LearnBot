package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentSchemaProfileResponse(
        UUID id,
        String schemaName,
        String description,
        List<String> documentTypes,
        List<String> entityTypes,
        List<String> relationTypes,
        boolean enabled,
        boolean defaultProfile,
        OffsetDateTime updatedAt
) {
}
