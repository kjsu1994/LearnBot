package com.learnbot.dto;

import java.util.List;

public record WebInspectResponse(
        String normalizedUrl,
        String host,
        boolean allowedDomain,
        Boolean robotsAllowed,
        String robotsReasonCode,
        String robotsMessage,
        boolean recursive,
        int maxDepth,
        int maxPages,
        String crawlScope,
        String renderMode,
        boolean playwrightEnabled,
        boolean chromiumAvailable,
        boolean renderRecommended,
        int staticTextLength,
        double staticTextDensity,
        int extractionQualityScore,
        String extractionQualityGrade,
        List<String> extractionQualitySignals,
        String selectorUsed,
        String extractionStrategy,
        int previewBlockCount,
        int linkCount,
        int sitemapUrlCount,
        List<String> recommendations
) {
}
