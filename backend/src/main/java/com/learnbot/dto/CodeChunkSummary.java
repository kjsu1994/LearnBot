package com.learnbot.dto;

import java.util.Map;
import java.util.UUID;

public record CodeChunkSummary(
        UUID id,
        int chunkIndex,
        String chunkType,
        String symbolName,
        String className,
        String methodName,
        String controlName,
        String eventName,
        int lineStart,
        int lineEnd,
        String preview,
        Map<String, Object> metadata
) {
}
