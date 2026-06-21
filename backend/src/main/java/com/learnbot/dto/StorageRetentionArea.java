package com.learnbot.dto;

public record StorageRetentionArea(
        String key,
        String label,
        String impact,
        int retentionDays,
        long candidates,
        long deleted,
        long estimatedBytes
) {
}
