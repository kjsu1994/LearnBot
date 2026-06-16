package com.learnbot.service;

import java.util.UUID;

public record CodeFileRecord(
        UUID id,
        UUID repositoryId,
        UUID indexVersion,
        String filePath,
        String language,
        String contentHash
) {
}
