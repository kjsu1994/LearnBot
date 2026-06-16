package com.learnbot.dto;

public record StoredObjectSummary(
        String bucket,
        String originalFilename,
        String contentType,
        long sizeBytes
) {
}
