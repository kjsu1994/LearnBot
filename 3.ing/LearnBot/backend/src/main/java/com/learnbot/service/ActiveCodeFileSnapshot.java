package com.learnbot.service;

import java.util.UUID;

public record ActiveCodeFileSnapshot(
        UUID fileId,
        String filePath,
        String contentHash,
        int chunkCount
) {
}
