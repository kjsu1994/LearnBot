package com.learnbot.service;

import java.util.UUID;

public record CodeRepositoryRecord(
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
        String lastIndexedCommit
) {
}
