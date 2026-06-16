package com.learnbot.service;

import java.util.Map;

public record ParsedCodeChunk(
        int chunkIndex,
        String chunkType,
        String symbolName,
        String className,
        String methodName,
        String namespaceName,
        String controlName,
        String eventName,
        int lineStart,
        int lineEnd,
        String content,
        Map<String, Object> metadata
) {
}
