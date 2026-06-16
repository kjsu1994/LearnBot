package com.learnbot.dto;

import java.util.Map;
import java.util.UUID;

public record CodeSearchResult(
        UUID chunkId,
        UUID repositoryId,
        UUID fileId,
        String repositoryName,
        String filePath,
        String chunkType,
        String symbolName,
        String className,
        String methodName,
        String namespaceName,
        String controlName,
        String eventName,
        int chunkIndex,
        int lineStart,
        int lineEnd,
        String content,
        double score,
        Map<String, Object> metadata
) {
}
