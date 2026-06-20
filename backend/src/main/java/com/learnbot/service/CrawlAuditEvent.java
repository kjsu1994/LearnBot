package com.learnbot.service;

import java.util.Map;
import java.util.UUID;

public record CrawlAuditEvent(
        UUID sourceId,
        String url,
        String host,
        boolean allowedDomain,
        Boolean robotsAllowed,
        Integer statusCode,
        boolean success,
        String reasonCode,
        Integer depth,
        String referrerUrl,
        String normalizedUrl,
        String contentType,
        Map<String, Object> metadata,
        String message
) {
}
