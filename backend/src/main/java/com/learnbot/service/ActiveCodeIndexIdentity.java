package com.learnbot.service;

import java.util.UUID;

public record ActiveCodeIndexIdentity(
        UUID repositoryId,
        UUID spaceId,
        UUID indexVersion,
        String contentFingerprint,
        String analyzerVersion,
        String indexSchemaVersion,
        String enrichmentStatus,
        String graphRevision,
        String diagnosticRevision
) {
    public ActiveCodeIndexIdentity {
        contentFingerprint = safe(contentFingerprint);
        analyzerVersion = safe(analyzerVersion);
        indexSchemaVersion = safe(indexSchemaVersion);
        enrichmentStatus = safe(enrichmentStatus);
        graphRevision = safe(graphRevision);
        diagnosticRevision = safe(diagnosticRevision);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
