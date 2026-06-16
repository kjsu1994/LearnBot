package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"))).thenReturn(page(
                "https://example.com/docs",
                text("Root", "Root documentation content with enough useful body text."),
                List.of(
                        URI.create("https://example.com/docs/install"),
                        URI.create("https://example.com/other"),
                        URI.create("https://other.example.com/docs/install"),
                        URI.create("https://example.com/docs/manual.pdf")
                )
        ));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs/install"))).thenReturn(page(
                "https://example.com/docs/install",
                text("Install", "Install documentation content with enough useful body text."),
                List.of()
        ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 2, 30);

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs", "https://example.com/docs/install");
        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.skippedCount()).isZero();
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

        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs"))).thenReturn(page(
                "https://example.com/docs",
                text("Root", duplicateBody),
                List.of(URI.create("https://example.com/docs/short"), URI.create("https://example.com/docs/copy"))
        ));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs/short"))).thenReturn(page(
                "https://example.com/docs/short",
                text("Short", "too short"),
                List.of()
        ));
        when(extractor.fetchPage(eq(sourceId), eq("https://example.com/docs/copy"))).thenReturn(page(
                "https://example.com/docs/copy",
                text("Copy", duplicateBody),
                List.of()
        ));

        WebCrawler.CrawlResult result = crawler.crawl(sourceId, "https://example.com/docs", 1, 30);

        assertThat(result.documents()).extracting(ExtractedDocument::sourceUri)
                .containsExactly("https://example.com/docs");
        assertThat(result.fetchedCount()).isEqualTo(3);
        assertThat(result.skippedCount()).isEqualTo(2);
        verify(extractor).auditSkipped(eq(sourceId), eq(URI.create("https://example.com/docs/short")), any());
        verify(extractor).auditSkipped(eq(sourceId), eq(URI.create("https://example.com/docs/copy")), any());
    }

    private WebPageExtractor.FetchedPage page(String url, ExtractedDocument document, List<URI> links) {
        return new WebPageExtractor.FetchedPage(URI.create(url), document, links, "example.com", true, 200);
    }

    private ExtractedDocument text(String title, String body) {
        return new ExtractedDocument(
                title,
                "https://example.com/docs" + ("Root".equals(title) ? "" : "/" + title.toLowerCase()),
                "text/html",
                "Page title: " + title + "\nURL: https://example.com/docs\n\n" + body,
                Map.of("bodyTextLength", body.length(), "host", "example.com")
        );
    }
}
