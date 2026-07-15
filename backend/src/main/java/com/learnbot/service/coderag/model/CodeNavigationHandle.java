package com.learnbot.service.coderag.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record CodeNavigationHandle(
        String handleId,
        Kind kind,
        String path,
        String symbol,
        UUID chunkId,
        int lineStart,
        int lineEnd,
        String sourceEvidenceId
) {
    public enum Kind {
        CALL,
        TYPE,
        DEFINITION
    }

    public CodeNavigationHandle {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        path = path == null ? "" : path.trim();
        symbol = symbol == null ? "" : symbol.trim();
        sourceEvidenceId = sourceEvidenceId == null ? "" : sourceEvidenceId.trim();
        if (path.isBlank() && symbol.isBlank()) {
            throw new IllegalArgumentException("path or symbol must be present");
        }
        if (sourceEvidenceId.isBlank()) {
            throw new IllegalArgumentException("sourceEvidenceId must not be blank");
        }
        lineStart = Math.max(0, lineStart);
        lineEnd = Math.max(lineStart, lineEnd);
        handleId = handleId == null || handleId.isBlank()
                ? stableId(kind, path, symbol, chunkId, lineStart, lineEnd, sourceEvidenceId)
                : handleId.trim();
    }

    public static CodeNavigationHandle of(
            Kind kind,
            String path,
            String symbol,
            UUID chunkId,
            int lineStart,
            int lineEnd,
            String sourceEvidenceId
    ) {
        return new CodeNavigationHandle("", kind, path, symbol, chunkId,
                lineStart, lineEnd, sourceEvidenceId);
    }

    private static String stableId(
            Kind kind,
            String path,
            String symbol,
            UUID chunkId,
            int lineStart,
            int lineEnd,
            String sourceEvidenceId
    ) {
        String key = String.join("\u001f", kind.name(), path, symbol,
                chunkId == null ? "" : chunkId.toString(), String.valueOf(lineStart),
                String.valueOf(lineEnd), sourceEvidenceId);
        return "navigation:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
