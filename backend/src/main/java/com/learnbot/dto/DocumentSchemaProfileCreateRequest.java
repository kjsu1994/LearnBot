package com.learnbot.dto;

import java.util.List;

public record DocumentSchemaProfileCreateRequest(
        String schemaName,
        String description,
        List<String> documentTypes,
        List<String> entityTypes,
        List<String> relationTypes,
        Boolean enabled,
        Boolean defaultProfile
) {
}
