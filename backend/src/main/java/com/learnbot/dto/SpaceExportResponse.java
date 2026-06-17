package com.learnbot.dto;

public record SpaceExportResponse(
        String fileName,
        String relativePath,
        long sizeBytes,
        SpaceTransferCounts counts
) {
}
