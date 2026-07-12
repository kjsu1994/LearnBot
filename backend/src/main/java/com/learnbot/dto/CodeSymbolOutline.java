package com.learnbot.dto;

import java.util.UUID;

public record CodeSymbolOutline(
        String entityId,
        String filePath,
        String kind,
        String name,
        String qualifiedName,
        int lineStart,
        int lineEnd,
        UUID chunkId,
        String analyzer,
        String authority,
        int totalInFile
) {
}
