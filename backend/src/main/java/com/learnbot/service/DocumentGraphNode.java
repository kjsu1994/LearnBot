package com.learnbot.service;

import java.util.Map;
import java.util.UUID;

public record DocumentGraphNode(
        String key,
        String type,
        String label,
        UUID documentId,
        UUID chunkId,
        Map<String, Object> metadata
) {
}
