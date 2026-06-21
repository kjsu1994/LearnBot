package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record StorageRetentionPreview(
        OffsetDateTime generatedAt,
        boolean dryRun,
        List<StorageRetentionArea> areas,
        long totalCandidates,
        long totalEstimatedBytes
) {
}
