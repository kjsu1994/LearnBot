package com.learnbot.service;

import java.util.UUID;

public record CodeGraphEnrichmentJob(
        UUID id,
        UUID repositoryId,
        UUID indexVersion,
        String status,
        int attempts
) {
}
