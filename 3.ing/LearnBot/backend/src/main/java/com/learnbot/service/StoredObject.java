package com.learnbot.service;

public record StoredObject(
        String bucket,
        String objectKey,
        String originalFilename,
        String contentType,
        long sizeBytes
) {
}
