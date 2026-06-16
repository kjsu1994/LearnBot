package com.learnbot.dto;

import java.util.UUID;

public record IngestResponse(
        UUID sourceId,
        UUID documentId,
        UUID spaceId,
        int chunkCount,
        String status
) {
}
