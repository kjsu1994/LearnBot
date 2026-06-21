package com.learnbot.service;

import java.util.UUID;

public record DocumentGraphJob(
        UUID id,
        UUID sourceId,
        UUID jobId,
        String status,
        int attempts,
        String leaseOwner
) {
}
