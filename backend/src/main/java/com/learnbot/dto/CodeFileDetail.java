package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record CodeFileDetail(
        UUID id,
        UUID repositoryId,
        String repositoryName,
        String filePath,
        String language,
        String content,
        List<CodeChunkSummary> chunks
) {
}
