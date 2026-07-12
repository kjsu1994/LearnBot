package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

final class CodeEvidenceId {
    private CodeEvidenceId() {
    }

    static String from(CodeSearchResult result) {
        if (result == null) {
            return "";
        }
        String indexVersion = metadataString(result, "indexVersion");
        String chunkId = result.chunkId() == null ? "unknown-chunk" : result.chunkId().toString();
        return (indexVersion.isBlank() ? "unknown-index" : indexVersion)
                + ":" + chunkId
                + ":" + Math.max(0, result.lineStart())
                + "-" + Math.max(0, result.lineEnd());
    }

    static String indexVersion(CodeSearchResult result) {
        return metadataString(result, "indexVersion");
    }

    private static String metadataString(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null || key == null) {
            return "";
        }
        Object value = result.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
