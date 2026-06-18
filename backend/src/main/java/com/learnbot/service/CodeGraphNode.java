package com.learnbot.service;

import java.util.Map;
import java.util.UUID;

public record CodeGraphNode(
        String key,
        String type,
        String name,
        String qualifiedName,
        String filePath,
        UUID chunkId,
        Map<String, Object> metadata
) {
}
