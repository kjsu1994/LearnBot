package com.learnbot.service;

import java.util.UUID;

public record DocumentEnrichmentJob(
        UUID id,
        UUID sourceId,
        UUID jobId,
        String status,
        int attempts,
        String leaseOwner
) {
}
