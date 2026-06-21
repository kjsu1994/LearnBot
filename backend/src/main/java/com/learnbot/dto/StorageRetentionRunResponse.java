package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record StorageRetentionRunResponse(
        OffsetDateTime executedAt,
        boolean dryRun,
        List<StorageRetentionArea> areas,
        long totalDeleted,
        long totalEstimatedBytes
) {
}
