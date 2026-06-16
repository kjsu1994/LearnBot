package com.learnbot.dto;

import java.util.Map;
import java.util.UUID;

public record CodeEvidence(
        int citationNumber,
        UUID chunkId,
        UUID repositoryId,
        UUID fileId,
        String repositoryName,
        String filePath,
        String chunkType,
        String symbolName,
        String className,
        String methodName,
        String controlName,
        String eventName,
        int lineStart,
        int lineEnd,
        String preview,
        double score,
        Map<String, Object> metadata
) {
}
