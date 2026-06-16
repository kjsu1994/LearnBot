package com.learnbot.service;

public record StoredFile(
        String filename,
        String contentType,
        byte[] content
) {
}
