package com.learnbot.dto;

import com.learnbot.dto.crawl.CrawlAuditInsight;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CrawlAuditSummary(
        UUID id,
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
        String message,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String category,
        String severity,
        boolean indexingBlocked,
        String userAction
) {
    public CrawlAuditSummary(
            UUID id,
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
            String message,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
        this(
                id,
                url,
                host,
                allowedDomain,
                robotsAllowed,
                statusCode,
                success,
                reasonCode,
                depth,
                referrerUrl,
                normalizedUrl,
                contentType,
                metadata,
                message,
                startedAt,
                finishedAt,
                CrawlAuditInsight.from(success, reasonCode)
        );
    }

    private CrawlAuditSummary(
            UUID id,
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
            String message,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            CrawlAuditInsight insight
    ) {
        this(
                id,
                url,
                host,
                allowedDomain,
                robotsAllowed,
                statusCode,
                success,
                reasonCode,
                depth,
                referrerUrl,
                normalizedUrl,
                contentType,
                metadata,
                message,
                startedAt,
                finishedAt,
                insight.category(),
                insight.severity(),
                insight.indexingBlocked(),
                insight.userAction()
        );
    }
}
