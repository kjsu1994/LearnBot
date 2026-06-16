package com.learnbot.service;

import java.util.Map;

public record ExtractedDocument(
        String title,
        String sourceUri,
        String contentType,
        String content,
        Map<String, Object> metadata
) {
}
