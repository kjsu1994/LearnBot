package com.learnbot.dto.crawl;

public record CrawlAuditInsight(
        String category,
        String severity,
        boolean indexingBlocked,
        String userAction
) {
    public static CrawlAuditInsight from(boolean success, String reasonCode) {
        String reason = reasonCode == null ? "" : reasonCode.trim().toUpperCase();
        if (success) {
            if (reason.startsWith("SITEMAP_")) {
                return new CrawlAuditInsight("SITEMAP", "INFO", false, "No action needed.");
            }
            if (reason.contains("ATTACHMENT")) {
                return new CrawlAuditInsight("ATTACHMENT", "INFO", false, "No action needed.");
            }
            return new CrawlAuditInsight("FETCHED", "INFO", false, "No action needed.");
        }
        if (reason.equals("DOMAIN_NOT_ALLOWED")) {
            return new CrawlAuditInsight("POLICY_BLOCK", "WARNING", true,
                    "Add the domain to the crawler allowlist or choose an allowed URL.");
        }
        if (reason.startsWith("ROBOTS_")) {
            return new CrawlAuditInsight("ROBOTS_BLOCK", "WARNING", true,
                    "Review robots.txt policy or choose a URL that permits crawling.");
        }
        if (reason.equals("SCHEME_MISMATCH")
                || reason.equals("SITE_MISMATCH")
                || reason.equals("HOST_MISMATCH")
                || reason.equals("OUT_OF_SCOPE")) {
            return new CrawlAuditInsight("SCOPE_BLOCK", "WARNING", true,
                    "Adjust crawl scope, max depth, or start from a URL inside the selected scope.");
        }
        if (reason.equals("LOW_CONTENT")
                || reason.equals("LOW_TEXT_DENSITY")
                || reason.equals("NAVIGATION_ONLY_PAGE")
                || reason.equals("LOW_EXTRACTION_QUALITY")) {
            return new CrawlAuditInsight("QUALITY_BLOCK", "WARNING", true,
                    "Try Playwright rendering, a deeper page URL, or a page with more extractable text.");
        }
        if (reason.equals("DUPLICATE_CONTENT")) {
            return new CrawlAuditInsight("DUPLICATE", "INFO", true,
                    "No action needed unless the duplicate page should be cited separately.");
        }
        if (reason.contains("PLAYWRIGHT") || reason.contains("STATIC_LOW_CONTENT")) {
            return new CrawlAuditInsight("RENDER_FALLBACK", "INFO", false,
                    "Review the rendered fallback result if the page still looks thin.");
        }
        if (reason.startsWith("SITEMAP_")) {
            return new CrawlAuditInsight("SITEMAP", "WARNING", true,
                    "Check sitemap availability or continue with discovered page links.");
        }
        if (reason.contains("ATTACHMENT")) {
            return new CrawlAuditInsight("ATTACHMENT", "WARNING", true,
                    "Check attachment type, size, and per-crawl attachment limits.");
        }
        if (reason.contains("FETCH") || reason.contains("TIMEOUT")) {
            return new CrawlAuditInsight("FETCH_ERROR", "ERROR", true,
                    "Retry later or verify that the URL is reachable from the LearnBot server.");
        }
        return new CrawlAuditInsight("SKIPPED", "WARNING", true,
                "Review the audit message and crawl settings.");
    }
}
