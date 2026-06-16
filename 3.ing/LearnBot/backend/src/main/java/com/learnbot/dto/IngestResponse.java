package com.learnbot.dto;

import java.util.UUID;

public record IngestResponse(
        UUID sourceId,
        UUID documentId,
        int chunkCount,
        String status
) {
}
