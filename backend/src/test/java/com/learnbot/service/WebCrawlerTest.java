package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebCrawlerTest {
    @Test
    void crawlsOnlySameHostDescendantPagesWithinLimits() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setMaxDepth(2);
        properties.getCrawler().setMaxPagesPerRequest(30);
        properties.getCrawler().setMinContentChars(20);
        WebPageExtractor extractor = mock(WebPageExtractor.class);
        WebCrawler crawler = new WebCrawler(properties, extractor);
        UUID sourceId = UUID.randomUUID();

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"), any())).thenReturn(page(
                "https://example.com/docs",
                text("Root", "Root documentation content with enough useful body text."),
                List.of(
                        URI.create("https://example.com/docs/install"),
                        URI.create("https://example.com/other"),
                        URI.create("https://other.example.com/docs/install"),
                        URI.create("https://example.com/docs/manual.pdf")
                )
        ));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs/install"), any())).thenReturn(page(
                "https://example.com/docs/install",
                text("Install", "Install documentation content with enough useful body text."),
                List.of()
        ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 2, 30);

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs", "https://example.com/docs/install");
        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.skippedCount()).isEqualTo(3);
        verify(extractor, never()).fetchPage(eq(sourceId), eq("https://example.com/other"));
        verify(extractor, never()).fetchPage(eq(sourceId), eq("https://other.example.com/docs/install"));
        verify(extractor, never()).fetchPage(eq(sourceId), eq("https://example.com/docs/manual.pdf"));
    }

    @Test
    void skipsShortAndDuplicatePagesAfterFetchingLinks() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setMaxDepth(1);
        properties.getCrawler().setMaxPagesPerRequest(30);
        properties.getCrawler().setMinContentChars(40);
        WebPageExtractor extractor = mock(WebPageExtractor.class);
        WebCrawler crawler = new WebCrawler(properties, extractor);
        UUID sourceId = UUID.randomUUID();
        String duplicateBody = "Shared documentation content that is long enough but duplicated.";

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"), any())).thenReturn(page(
                "https://example.com/docs",
                text("Root", duplicateBody),
                List.of(URI.create("https://example.com/docs/short"), URI.create("https://example.com/docs/copy"))
        ));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs/short"), any())).thenReturn(page(
                "https://example.com/docs/short",
                text("Short", "too short"),
                List.of()
        ));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs/copy"), any())).thenReturn(page(
                "https://example.com/docs/copy",
                text("Copy", duplicateBody),
                List.of()
        ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 1, 30);

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs");
        assertThat(result.fetchedCount()).isEqualTo(3);
        assertThat(result.skippedCount()).isEqualTo(2);
        verify(extractor).auditSkipped(eq(sourceId), eq(URI.create("https://example.com/docs/short")),
                eq("LOW_CONTENT"), eq(1), eq("https://example.com/docs"), eq("text/html"), any(), any());
        verify(extractor).auditSkipped(eq(sourceId), eq(URI.create("https://example.com/docs/copy")),
                eq("DUPLICATE_CONTENT"), eq(1), eq("https://example.com/docs"), eq("text/html"), any(), any());
    }

    @Test
    void addsSitemapUrlsAsBoundedSeeds() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setMaxDepth(1);
        properties.getCrawler().setMaxPagesPerRequest(30);
        properties.getCrawler().setMinContentChars(20);
        properties.getCrawler().setMaxSitemapUrls(2);
        WebPageExtractor extractor = mock(WebPageExtractor.class);
        WebCrawler crawler = new WebCrawler(properties, extractor);
        UUID sourceId = UUID.randomUUID();

        when(extractor.sitemapUrls(eq(sourceId), eq(URI.create("https://example.com/docs")), eq(2)))
                .thenReturn(List.of(URI.create("https://example.com/docs/from-sitemap")));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"), any())).thenReturn(page(
                "https://example.com/docs",
                text("Root", "Root documentation content with enough useful body text."),
                List.of()
        ));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs/from-sitemap"), any())).thenReturn(page(
                "https://example.com/docs/from-sitemap",
                textAt("Sitemap", "https://example.com/docs/from-sitemap", "Sitemap page content with enough useful body text."),
                List.of()
        ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 1, 30,
                new CrawlOptions(CrawlScope.START_PATH, RobotsFailurePolicy.FAIL_CLOSED, false, true, WebRenderMode.STATIC));

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs", "https://example.com/docs/from-sitemap");
        verify(extractor).sitemapUrls(eq(sourceId), eq(URI.create("https://example.com/docs")), eq(2));
    }

    @Test
    void limitsAttachmentCollectionWithoutFailingCrawl() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setMaxDepth(1);
        properties.getCrawler().setMaxPagesPerRequest(30);
        properties.getCrawler().setMinContentChars(20);
        properties.getCrawler().setMaxAttachmentsPerCrawl(1);
        WebPageExtractor extractor = mock(WebPageExtractor.class);
        WebCrawler crawler = new WebCrawler(properties, extractor);
        UUID sourceId = UUID.randomUUID();

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"), any())).thenReturn(page(
                "https://example.com/docs",
                text("Root", "Root documentation content with enough useful body text."),
                List.of(URI.create("https://example.com/docs/a.pdf"), URI.create("https://example.com/docs/b.pdf"))
        ));
        when(extractor.fetchAttachment(eq(sourceId), eq(URI.create("https://example.com/docs/a.pdf")),
                eq("https://example.com/docs"), any())).thenReturn(textAt(
                "Attachment A",
                "https://example.com/docs/a.pdf",
                "Attachment content with enough useful body text."
        ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 1, 30,
                new CrawlOptions(CrawlScope.START_PATH, RobotsFailurePolicy.FAIL_CLOSED, true, false, WebRenderMode.STATIC));

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs", "https://example.com/docs/a.pdf");
        assertThat(result.skippedCount()).isEqualTo(1);
        verify(extractor, never()).fetchAttachment(eq(sourceId), eq(URI.create("https://example.com/docs/b.pdf")), any(), any());
        verify(extractor).auditSkipped(eq(sourceId), eq(URI.create("https://example.com/docs/b.pdf")),
                eq("ATTACHMENT_LIMIT_REACHED"), eq(1), eq("https://example.com/docs"), eq(null), any(), any());
    }

    @Test
    void retriesWeakStaticPageWithPlaywrightFallback() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setMaxDepth(0);
        properties.getCrawler().setMaxPagesPerRequest(30);
        properties.getCrawler().setMinContentChars(30);
        properties.getCrawler().setPlaywrightEnabled(true);
        WebPageExtractor extractor = mock(WebPageExtractor.class);
        WebCrawler crawler = new WebCrawler(properties, extractor);
        UUID sourceId = UUID.randomUUID();

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"), any()))
                .thenReturn(page(
                        "https://example.com/docs",
                        text("Root", "thin"),
                        List.of()
                ))
                .thenReturn(page(
                        "https://example.com/docs",
                        text("Root", "Rendered documentation content with enough useful body text after JavaScript runs."),
                        List.of()
                ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 0, 30,
                new CrawlOptions(CrawlScope.START_PATH, RobotsFailurePolicy.FAIL_CLOSED, false, false, WebRenderMode.STATIC));

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs");
        assertThat(result.skippedCount()).isZero();
        verify(extractor, times(2)).fetchPage(eq(sourceId), eq("https://example.com/docs"), any());
        verify(extractor).fetchPage(eq(sourceId), eq("https://example.com/docs"),
                argThat(options -> options.renderMode() == WebRenderMode.PLAYWRIGHT_FALLBACK));
        verify(extractor).auditSkipped(eq(sourceId), eq(URI.create("https://example.com/docs")),
                eq("STATIC_LOW_CONTENT_PLAYWRIGHT_RETRY"), eq(0), eq(null), eq(null), any(), any());
    }

    @Test
    void retriesLowQualityStaticPageBasedOnExtractionSignals() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setMaxDepth(0);
        properties.getCrawler().setMaxPagesPerRequest(30);
        properties.getCrawler().setMinContentChars(30);
        properties.getCrawler().setPlaywrightEnabled(true);
        WebPageExtractor extractor = mock(WebPageExtractor.class);
        WebCrawler crawler = new WebCrawler(properties, extractor);
        UUID sourceId = UUID.randomUUID();

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"), any()))
                .thenReturn(page(
                        "https://example.com/docs",
                        textWithMetadata(
                                "Root",
                                "https://example.com/docs",
                                "Static content has enough text but extraction quality says rendering is needed.",
                                Map.of(
                                        "bodyTextLength", 78,
                                        "host", "example.com",
                                        "extractionQualityGrade", "LOW",
                                        "extractionQualitySignals", List.of("RENDER_RECOMMENDED")
                                )
                        ),
                        List.of()
                ))
                .thenReturn(page(
                        "https://example.com/docs",
                        text("Root", "Rendered documentation content with enough useful body text after JavaScript runs."),
                        List.of()
                ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 0, 30,
                new CrawlOptions(CrawlScope.START_PATH, RobotsFailurePolicy.FAIL_CLOSED, false, false, WebRenderMode.STATIC));

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs");
        verify(extractor).fetchPage(eq(sourceId), eq("https://example.com/docs"),
                argThat(options -> options.renderMode() == WebRenderMode.PLAYWRIGHT_FALLBACK));
    }

    @Test
    void indexesWeakStartPageWithLowQualityMetadataWhenNoFallbackIsAvailable() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setMaxDepth(0);
        properties.getCrawler().setMaxPagesPerRequest(30);
        properties.getCrawler().setMinContentChars(30);
        properties.getCrawler().setPlaywrightEnabled(false);
        WebPageExtractor extractor = mock(WebPageExtractor.class);
        WebCrawler crawler = new WebCrawler(properties, extractor);
        UUID sourceId = UUID.randomUUID();

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"), any()))
                .thenReturn(page(
                        "https://example.com/docs",
                        text("Root", "thin"),
                        List.of()
                ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 0, 30,
                new CrawlOptions(CrawlScope.START_PATH, RobotsFailurePolicy.FAIL_CLOSED, false, false, WebRenderMode.STATIC));

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).metadata())
                .containsEntry("crawlQuality", "LOW")
                .containsEntry("crawlQualityReason", "LOW_CONTENT")
                .containsEntry("extractionQualityGrade", "LOW")
                .containsEntry("extractionQualityScore", 25);
        assertThat(result.skippedCount()).isZero();
        verify(extractor).auditSkipped(eq(sourceId), eq(URI.create("https://example.com/docs")),
                eq("START_PAGE_INDEXED_WITH_LOW_QUALITY"), eq(0), eq(null), eq("text/html"), any(), any());
    }

    private WebPageExtractor.FetchedPage page(String url, ExtractedDocument document, List<URI> links) {
        return new WebPageExtractor.FetchedPage(URI.create(url), document, links, "example.com", true, 200);
    }

    private ExtractedDocument text(String title, String body) {
        return textAt(title,
                "https://example.com/docs" + ("Root".equals(title) ? "" : "/" + title.toLowerCase()),
                body);
    }

    private ExtractedDocument textAt(String title, String sourceUri, String body) {
        return textWithMetadata(title, sourceUri, body, Map.of("bodyTextLength", body.length(), "host", "example.com"));
    }

    private ExtractedDocument textWithMetadata(String title, String sourceUri, String body, Map<String, Object> metadata) {
        return new ExtractedDocument(
                title,
                sourceUri,
                "text/html",
                "Page title: " + title + "\nURL: " + sourceUri + "\n\n" + body,
                metadata
        );
    }
}
