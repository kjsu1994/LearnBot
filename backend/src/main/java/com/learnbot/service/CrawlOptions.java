package com.learnbot.service;

public record CrawlOptions(
        CrawlScope scope,
        RobotsFailurePolicy robotsFailurePolicy,
        boolean includeAttachments,
        boolean useSitemap,
        WebRenderMode renderMode
) {
    public static CrawlOptions defaults() {
        return new CrawlOptions(
                CrawlScope.START_PATH,
                RobotsFailurePolicy.FAIL_CLOSED,
                false,
                false,
                WebRenderMode.STATIC
        );
    }

    public CrawlOptions normalized() {
        return new CrawlOptions(
                scope == null ? CrawlScope.START_PATH : scope,
                robotsFailurePolicy == null ? RobotsFailurePolicy.FAIL_CLOSED : robotsFailurePolicy,
                includeAttachments,
                useSitemap,
                renderMode == null ? WebRenderMode.STATIC : renderMode
        );
    }
}
