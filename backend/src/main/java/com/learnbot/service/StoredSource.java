package com.learnbot.service;

import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;

import java.util.Map;
import java.util.UUID;

public record StoredSource(
        UUID id,
        SourceType type,
        String name,
        String location,
        SourceStatus status,
        Map<String, Object> metadata
) {
}
