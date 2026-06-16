package com.learnbot.service;

import java.nio.file.Path;

public record CodeFileCandidate(
        Path absolutePath,
        String relativePath,
        String language,
        long sizeBytes
) {
}
