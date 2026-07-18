package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;

public final class CodeEvidenceId {
    private CodeEvidenceId() {
    }

    public static String from(CodeSearchResult result) {
        if (result == null) {
            return "";
        }
        String indexVersion = metadataString(result, "indexVersion");
        String chunkId = result.chunkId() == null ? "unknown-chunk" : result.chunkId().toString();
        int lineStart = metadataInteger(result, "sourceLineStart", result.lineStart());
        int lineEnd = Math.max(lineStart,
                metadataInteger(result, "sourceLineEnd", result.lineEnd()));
        return (indexVersion.isBlank() ? "unknown-index" : indexVersion)
                + ":" + chunkId
                + ":" + lineStart
                + "-" + lineEnd;
    }

    public static String indexVersion(CodeSearchResult result) {
        return metadataString(result, "indexVersion");
    }

    private static String metadataString(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null || key == null) {
            return "";
        }
        Object value = result.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int metadataInteger(CodeSearchResult result, String key, int fallback) {
        Object value = result == null || result.metadata() == null
                ? null : result.metadata().get(key);
        if (value instanceof Number number) return Math.max(0, number.intValue());
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
            } catch (NumberFormatException ignored) {
                // Fall back to the source result range.
            }
        }
        return Math.max(0, fallback);
    }
}
