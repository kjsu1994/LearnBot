package com.learnbot.dto.crawl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlAuditInsightTest {
    @Test
    void classifiesPolicyAndRobotsBlocksAsIndexingBlocked() {
        CrawlAuditInsight allowlist = CrawlAuditInsight.from(false, "DOMAIN_NOT_ALLOWED");
        CrawlAuditInsight robots = CrawlAuditInsight.from(false, "ROBOTS_DISALLOW");

        assertThat(allowlist.category()).isEqualTo("POLICY_BLOCK");
        assertThat(allowlist.indexingBlocked()).isTrue();
        assertThat(allowlist.userAction()).contains("allowlist");
        assertThat(robots.category()).isEqualTo("ROBOTS_BLOCK");
        assertThat(robots.indexingBlocked()).isTrue();
        assertThat(robots.userAction()).contains("robots.txt");
    }

    @Test
    void classifiesQualityDuplicateAndFetchReasons() {
        assertThat(CrawlAuditInsight.from(false, "LOW_CONTENT").category()).isEqualTo("QUALITY_BLOCK");
        assertThat(CrawlAuditInsight.from(false, "DUPLICATE_CONTENT").category()).isEqualTo("DUPLICATE");
        assertThat(CrawlAuditInsight.from(false, "FETCH_FAILED").category()).isEqualTo("FETCH_ERROR");
    }

    @Test
    void successfulFetchDoesNotBlockIndexing() {
        CrawlAuditInsight insight = CrawlAuditInsight.from(true, null);

        assertThat(insight.category()).isEqualTo("FETCHED");
        assertThat(insight.severity()).isEqualTo("INFO");
        assertThat(insight.indexingBlocked()).isFalse();
    }
}
