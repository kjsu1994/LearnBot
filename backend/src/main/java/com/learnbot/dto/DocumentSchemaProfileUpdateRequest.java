package com.learnbot.dto;

import java.util.List;

public record DocumentSchemaProfileUpdateRequest(
        String description,
        List<String> documentTypes,
        List<String> entityTypes,
        List<String> relationTypes,
        Boolean enabled,
        Boolean defaultProfile
) {
}
