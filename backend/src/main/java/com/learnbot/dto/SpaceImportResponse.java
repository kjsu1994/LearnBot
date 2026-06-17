package com.learnbot.dto;

public record SpaceImportResponse(
        SpaceTransferCounts imported,
        SpaceTransferCounts skipped,
        String message
) {
}
