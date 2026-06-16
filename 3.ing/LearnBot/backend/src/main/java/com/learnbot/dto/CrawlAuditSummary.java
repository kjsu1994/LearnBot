package com.learnbot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CrawlAuditSummary(
        UUID id,
        String url,
        String host,
        boolean allowedDomain,
        Boolean robotsAllowed,
        Integer statusCode,
        boolean success,
        String message,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
}
