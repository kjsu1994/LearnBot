package com.learnbot.service;

import java.util.Map;
import java.util.UUID;

public record CodeGraphEdge(
        String sourceKey,
        String targetKey,
        String type,
        double confidence,
        UUID evidenceChunkId,
        Map<String, Object> metadata
) {
}
