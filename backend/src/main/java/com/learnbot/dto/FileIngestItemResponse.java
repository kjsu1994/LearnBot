package com.learnbot.dto;

public record FileIngestItemResponse(
        String filename,
        boolean success,
        IngestResponse response,
        String errorMessage
) {
}
