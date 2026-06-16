package com.learnbot.service;

import java.util.UUID;

public record CodeRepositoryRecord(
        UUID id,
        String name,
        String gitUrl,
        String branch,
        String authType,
        String localPath,
        String status,
        String lastIndexedCommit
) {
}
