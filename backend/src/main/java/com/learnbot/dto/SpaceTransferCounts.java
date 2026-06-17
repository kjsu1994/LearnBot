package com.learnbot.dto;

public record SpaceTransferCounts(
        int documents,
        int documentChunks,
        int sourceObjects,
        int codeRepositories,
        int codeFiles,
        int codeChunks
) {
    public static SpaceTransferCounts empty() {
        return new SpaceTransferCounts(0, 0, 0, 0, 0, 0);
    }

    public SpaceTransferCounts plus(SpaceTransferCounts other) {
        return new SpaceTransferCounts(
                documents + other.documents,
                documentChunks + other.documentChunks,
                sourceObjects + other.sourceObjects,
                codeRepositories + other.codeRepositories,
                codeFiles + other.codeFiles,
                codeChunks + other.codeChunks
        );
    }
}
