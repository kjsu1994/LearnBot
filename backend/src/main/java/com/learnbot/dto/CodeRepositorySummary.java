package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CodeRepositorySummary(
        UUID id,
        UUID spaceId,
        String name,
        String sourceType,
        String sourceLabel,
        String sourceHash,
        String gitUrl,
        String branch,
        String authType,
        String localPath,
        String status,
        String lastIndexedCommit,
        String contentFingerprint,
        String worktreeState,
        String analyzerVersion,
        String indexSchemaVersion,
        String errorMessage,
        boolean credentialStored,
        int activeFileCount,
        int activeChunkCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
