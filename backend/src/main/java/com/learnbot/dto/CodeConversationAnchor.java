package com.learnbot.dto;

import java.util.UUID;

public record CodeConversationAnchor(
        UUID chunkId,
        String filePath,
        String symbolName,
        String className,
        String methodName,
        int lineStart,
        int lineEnd
) {
}
