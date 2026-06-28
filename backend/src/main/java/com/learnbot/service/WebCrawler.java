package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class WebCrawler {
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".7z", ".avi", ".css", ".doc", ".docx", ".gif", ".gz", ".ico", ".jpeg", ".jpg",
            ".js", ".json", ".mp3", ".mp4", ".pdf", ".png", ".ppt", ".pptx", ".rar", ".rss",
            ".svg", ".tar", ".webp", ".xls", ".xlsx", ".xml", ".zip"
    );
    private static final List<String> EXCLUDED_PATH_PARTS = List.of(
            "/login", "/signin", "/signup", "/logout", "/search", "/tag/", "/tags/",
            "/attachment", "/attachments", "/download", "/uploads", "/print", "/share",
            "/cart", "/account", "/admin"
    );

    private final LearnBotProperties properties;
    private final WebPageExtractor extractor;

    public WebCrawler(LearnBotProperties properties, WebPageExtractor extractor) {
        this.properties = properties;
        this.extractor = extractor;
    }

    public CrawlResult crawl(UUID sourceId, String startUrl, int maxDepth, int maxPages) {
        return crawl(sourceId, startUrl, maxDepth, maxPages, CrawlOptions.defaults());
    }

    public CrawlResult crawl(UUID sourceId, String startUrl, int maxDepth, int maxPages, CrawlOptions options) {
        CrawlOptions safeOptions = options == null ? CrawlOptions.defaults() : options.normalized();
        URI startUri = normalizeUri(URI.create(startUrl));
        String rootHost = requireHost(startUri);
        String scopePath = scopePath(startUri);
        int safeDepth = Math.max(0, Math.min(maxDepth, properties.getCrawler().getMaxDepth()));
        int safeMaxPages = Math.max(1, Math.min(maxPages, properties.getCrawler().getMaxPagesPerRequest()));

        ArrayDeque<CrawlUrl> queue = new ArrayDeque<>();
        Set<String> queuedOrVisited = new HashSet<>();
        Set<String> contentSignatures = new HashSet<>();
        List<ExtractedDocument> documents = new ArrayList<>();
        int fetchedCount = 0;
        int skippedCount = 0;
        int attachmentCount = 0;

        enqueue(queue, queuedOrVisited, startUri, 0);
        if (safeOptions.useSitemap()) {
            for (URI sitemapUri : extractor.sitemapUrls(sourceId, startUri, properties.getCrawler().getMaxSitemapUrls())) {
                URI normalized;
                try {
                    normalized = normalizeUri(sitemapUri);
                } catch (RuntimeException ex) {
                    skippedCount++;
                    extractor.auditSkipped(sourceId, sitemapUri, "INVALID_URL", 0, startUri.toString(), null,
                            ex.getMessage(), Map.of("seedSource", "sitemap"));
                    continue;
                }
                ScopeDecision scopeDecision = scopeDecision(startUri, rootHost, scopePath, normalized, safeOptions.scope());
                if (!scopeDecision.allowed()) {
                    skippedCount++;
                    extractor.auditSkipped(sourceId, normalized, scopeDecision.reasonCode(), 0, startUri.toString(), null,
                            scopeDecision.message(), Map.of("seedSource", "sitemap"));
                    continue;
                }
                ExclusionDecision exclusionDecision = exclusionDecision(normalized, safeOptions);
                if (exclusionDecision.excluded() && !exclusionDecision.attachment()) {
                    skippedCount++;
                    extractor.auditSkipped(sourceId, normalized, exclusionDecision.reasonCode(), 0, startUri.toString(), null,
                            exclusionDecision.message(), Map.of("seedSource", "sitemap"));
                    continue;
                }
                enqueue(queue, queuedOrVisited, normalized, 0, startUri);
            }
        }
        while (!queue.isEmpty() && fetchedCount < safeMaxPages) {
            CrawlUrl current = queue.removeFirst();
            ScopeDecision currentScope = scopeDecision(startUri, rootHost, scopePath, current.uri(), safeOptions.scope());
            if (!currentScope.allowed()) {
                skippedCount++;
                extractor.auditSkipped(sourceId, current.uri(), currentScope.reasonCode(), current.depth(),
                        current.referrer() == null ? null : current.referrer().toString(), null, currentScope.message(), Map.of());
                continue;
            }
            ExclusionDecision currentExclusion = exclusionDecision(current.uri(), safeOptions);
            if (currentExclusion.excluded()) {
                if (currentExclusion.attachment() && safeOptions.includeAttachments()) {
                    if (attachmentCount >= Math.max(0, properties.getCrawler().getMaxAttachmentsPerCrawl())) {
                        skippedCount++;
                        extractor.auditSkipped(sourceId, current.uri(), "ATTACHMENT_LIMIT_REACHED", current.depth(),
                                current.referrer() == null ? null : current.referrer().toString(), null,
                                "Skipped attachment because the per-crawl attachment limit was reached.",
                                Map.of("maxAttachmentsPerCrawl", Math.max(0, properties.getCrawler().getMaxAttachmentsPerCrawl())));
                        continue;
                    }
                    ExtractedDocument attachment = extractor.fetchAttachment(
                            sourceId,
                            current.uri(),
                            current.referrer() == null ? null : current.referrer().toString(),
                            safeOptions
                    );
                    if (attachment != null) {
                        attachmentCount++;
                        documents.add(withCrawlMetadata(attachment, startUri, current.depth(), current.referrer()));
                    } else {
                        skippedCount++;
                    }
                } else {
                    skippedCount++;
                    extractor.auditSkipped(sourceId, current.uri(), currentExclusion.reasonCode(), current.depth(),
                            current.referrer() == null ? null : current.referrer().toString(), null, currentExclusion.message(), Map.of());
                }
                continue;
            }

            WebPageExtractor.FetchedPage page;
            try {
                page = extractor.fetchPage(sourceId, current.uri().toString(), safeOptions);
                fetchedCount++;
            } catch (RuntimeException ex) {
                skippedCount++;
                extractor.auditSkipped(sourceId, current.uri(), "FETCH_FAILED", current.depth(),
                        current.referrer() == null ? null : current.referrer().toString(), null, ex.getMessage(), Map.of());
                continue;
            }

            for (URI link : page.links()) {
                if (current.depth() < safeDepth) {
                    skippedCount += enqueueIfCrawlable(
                            queue,
                            queuedOrVisited,
                            sourceId,
                            startUri,
                            rootHost,
                            scopePath,
                            link,
                            current.depth() + 1,
                            current.uri(),
                            safeOptions
                    );
                }
            }

            SkipDecision skipDecision = skipDecision(page.document(), contentSignatures, current.depth());
            if (skipDecision != null && shouldRetryWithPlaywright(skipDecision, safeOptions)) {
                WebPageExtractor.FetchedPage renderedPage = retryWithPlaywright(sourceId, current, safeOptions, skipDecision);
                if (renderedPage != null) {
                    page = renderedPage;
                    skipDecision = skipDecision(page.document(), contentSignatures, current.depth());
                }
            }
            if (skipDecision != null) {
                if (current.depth() == 0) {
                    extractor.auditSkipped(sourceId, page.uri(), "START_PAGE_INDEXED_WITH_LOW_QUALITY", current.depth(),
                            current.referrer() == null ? null : current.referrer().toString(), page.document().contentType(),
                            "Start page was indexed despite weak extraction so the crawl can fall back gracefully. " + skipDecision.message(),
                            skipDecision.metadata());
                    documents.add(withCrawlMetadata(withQualityMetadata(page.document(), skipDecision), startUri, current.depth(), current.referrer()));
                    continue;
                }
                skippedCount++;
                extractor.auditSkipped(sourceId, page.uri(), skipDecision.reasonCode(), current.depth(),
                        current.referrer() == null ? null : current.referrer().toString(), page.document().contentType(),
                        skipDecision.message(), skipDecision.metadata());
                continue;
            }
            documents.add(withCrawlMetadata(page.document(), startUri, current.depth(), current.referrer()));
        }

        return new CrawlResult(documents, fetchedCount, skippedCount);
    }

    private ExtractedDocument withQualityMetadata(ExtractedDocument document, SkipDecision decision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (document.metadata() != null) {
            metadata.putAll(document.metadata());
        }
        metadata.put("crawlQuality", "LOW");
        metadata.put("crawlQualityReason", decision.reasonCode());
        metadata.put("crawlQualityMessage", decision.message());
        if (decision.metadata() != null && !decision.metadata().isEmpty()) {
            metadata.put("crawlQualityDetails", decision.metadata());
        }
        return new ExtractedDocument(
                document.title(),
                document.sourceUri(),
                document.contentType(),
                document.content(),
                metadata
        );
    }

    private boolean shouldRetryWithPlaywright(SkipDecision skipDecision, CrawlOptions options) {
        if (skipDecision == null || options == null || options.renderMode() != WebRenderMode.STATIC) {
            return false;
        }
        if (!properties.getCrawler().isPlaywrightEnabled()) {
            return false;
        }
        return "LOW_CONTENT".equals(skipDecision.reasonCode())
                || "NAVIGATION_ONLY_PAGE".equals(skipDecision.reasonCode())
                || "LOW_TEXT_DENSITY".equals(skipDecision.reasonCode());
    }

    private WebPageExtractor.FetchedPage retryWithPlaywright(UUID sourceId, CrawlUrl current, CrawlOptions options, SkipDecision skipDecision) {
        CrawlOptions fallbackOptions = new CrawlOptions(
                options.scope(),
                options.robotsFailurePolicy(),
                options.includeAttachments(),
                options.useSitemap(),
                WebRenderMode.PLAYWRIGHT_FALLBACK
        );
        extractor.auditSkipped(sourceId, current.uri(), "STATIC_LOW_CONTENT_PLAYWRIGHT_RETRY", current.depth(),
                current.referrer() == null ? null : current.referrer().toString(), null,
                "Static crawl result was weak; retrying with Playwright fallback.",
                skipDecision.metadata());
        try {
            return extractor.fetchPage(sourceId, current.uri().toString(), fallbackOptions);
        } catch (RuntimeException ex) {
            extractor.auditSkipped(sourceId, current.uri(), "PLAYWRIGHT_RETRY_FAILED_STATIC_FALLBACK", current.depth(),
                    current.referrer() == null ? null : current.referrer().toString(), null,
                    "Playwright retry failed; keeping the static crawl result. " + ex.getMessage(), Map.of("error", ex.getMessage()));
            return null;
        }
    }

    private int enqueueIfCrawlable(
            ArrayDeque<CrawlUrl> queue,
            Set<String> queuedOrVisited,
            UUID sourceId,
            URI startUri,
            String rootHost,
            String scopePath,
            URI link,
            int depth,
            URI referrer,
            CrawlOptions options
    ) {
        URI normalized;
        try {
            normalized = normalizeUri(link);
        } catch (RuntimeException ex) {
            extractor.auditSkipped(sourceId, link, "INVALID_URL", depth, referrer.toString(), null, ex.getMessage(), Map.of());
            return 1;
        }
        ScopeDecision scopeDecision = scopeDecision(startUri, rootHost, scopePath, normalized, options.scope());
        if (!scopeDecision.allowed()) {
            extractor.auditSkipped(sourceId, normalized, scopeDecision.reasonCode(), depth, referrer.toString(), null, scopeDecision.message(), Map.of());
            return 1;
        }
        ExclusionDecision exclusionDecision = exclusionDecision(normalized, options);
        if (exclusionDecision.excluded() && !(exclusionDecision.attachment() && options.includeAttachments())) {
            extractor.auditSkipped(sourceId, normalized, exclusionDecision.reasonCode(), depth, referrer.toString(), null, exclusionDecision.message(), Map.of());
            return 1;
        }
        enqueue(queue, queuedOrVisited, normalized, depth, referrer);
        return 0;
    }

    private void enqueue(ArrayDeque<CrawlUrl> queue, Set<String> queuedOrVisited, URI uri, int depth) {
        enqueue(queue, queuedOrVisited, uri, depth, null);
    }

    private void enqueue(ArrayDeque<CrawlUrl> queue, Set<String> queuedOrVisited, URI uri, int depth, URI referrer) {
        String key = uri.toString();
        if (queuedOrVisited.add(key)) {
            queue.add(new CrawlUrl(uri, depth, referrer));
        }
    }

    private SkipDecision skipDecision(ExtractedDocument document, Set<String> contentSignatures, int depth) {
        String signature = contentSignature(document.content());
        boolean duplicate = !contentSignatures.add(signature);
        int bodyLength = bodyTextLength(document);
        int minContent = depth > 0
                ? Math.max(properties.getCrawler().getMinContentChars(), properties.getCrawler().getRecursiveMinContentChars())
                : properties.getCrawler().getMinContentChars();
        if (duplicate && bodyLength >= Math.min(40, minContent)) {
            return new SkipDecision("DUPLICATE_CONTENT", "Skipped page because normalized text is duplicated.", Map.of());
        }
        if (bodyLength < minContent) {
            return new SkipDecision("LOW_CONTENT",
                    "Skipped page because extractable body text is too short: " + bodyLength + " chars.",
                    Map.of("bodyTextLength", bodyLength, "minContentChars", minContent));
        }
        double density = textDensity(document);
        if (depth > 0 && density > 0 && density < properties.getCrawler().getMinTextDensity()) {
            return new SkipDecision("LOW_TEXT_DENSITY",
                    "Skipped page because text density is too low: " + String.format(Locale.ROOT, "%.3f", density) + ".",
                    Map.of("bodyTextLength", bodyLength, "textDensity", density, "minTextDensity", properties.getCrawler().getMinTextDensity()));
        }
        int previewBlockCount = previewBlockCount(document);
        if (depth > 0 && previewBlockCount >= 0 && previewBlockCount <= 1 && bodyLength < minContent * 2) {
            return new SkipDecision("NAVIGATION_ONLY_PAGE",
                    "Skipped page because it appears to contain navigation or shell content only.",
                    Map.of("bodyTextLength", bodyLength, "previewBlockCount", previewBlockCount));
        }
        if (duplicate) {
            return new SkipDecision("DUPLICATE_CONTENT", "Skipped page because normalized text is duplicated.", Map.of());
        }
        return null;
    }

    private int bodyTextLength(ExtractedDocument document) {
        Object value = document.metadata() == null ? null : document.metadata().get("bodyTextLength");
        if (value instanceof Number number) {
            return number.intValue();
        }
        return document.content() == null ? 0 : document.content().length();
    }

    private double textDensity(ExtractedDocument document) {
        Object value = document.metadata() == null ? null : document.metadata().get("htmlTextDensity");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    private int previewBlockCount(ExtractedDocument document) {
        Object value = document.metadata() == null ? null : document.metadata().get("webPreviewBlocks");
        return value instanceof List<?> list ? list.size() : -1;
    }

    private String contentSignature(String content) {
        String normalized = content == null ? "" : content
                .replaceAll("(?m)^(Page title|URL|Heading):.*\\R?", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    private ExtractedDocument withCrawlMetadata(ExtractedDocument document, URI rootUri, int depth, URI referrer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (document.metadata() != null) {
            metadata.putAll(document.metadata());
        }
        metadata.put("rootUrl", rootUri.toString());
        metadata.put("crawlDepth", depth);
        if (referrer != null) {
            metadata.put("referrerUrl", referrer.toString());
        }
        return new ExtractedDocument(
                document.title(),
                document.sourceUri(),
                document.contentType(),
                document.content(),
                metadata
        );
    }

    private ScopeDecision scopeDecision(URI startUri, String rootHost, String scopePath, URI candidate, CrawlScope scope) {
        if (!sameScheme(startUri, candidate)) {
            return new ScopeDecision(false, "SCHEME_MISMATCH", "Skipped URL because scheme is outside crawl scope.");
        }
        String candidateHost = candidate.getHost() == null ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
        if (scope == CrawlScope.ALLOWLIST) {
            return new ScopeDecision(true, null, "");
        }
        if (scope == CrawlScope.SAME_SITE) {
            if (!siteDomain(rootHost).equals(siteDomain(candidateHost))) {
                return new ScopeDecision(false, "SITE_MISMATCH", "Skipped URL because site domain is outside crawl scope.");
            }
            return new ScopeDecision(true, null, "");
        }
        if (!rootHost.equals(candidateHost)) {
            return new ScopeDecision(false, "HOST_MISMATCH", "Skipped URL because host is outside crawl scope.");
        }
        if (scope == CrawlScope.SAME_HOST) {
            return new ScopeDecision(true, null, "");
        }
        String path = normalizedPath(candidate);
        if ("/".equals(scopePath)) {
            return new ScopeDecision(true, null, "");
        }
        boolean allowed = path.equals(scopePath) || path.startsWith(scopePath.endsWith("/") ? scopePath : scopePath + "/");
        return allowed
                ? new ScopeDecision(true, null, "")
                : new ScopeDecision(false, "PATH_SCOPE_MISMATCH", "Skipped URL because path is outside the start URL subtree.");
    }

    private boolean sameScheme(URI startUri, URI candidate) {
        return startUri.getScheme() != null
                && candidate.getScheme() != null
                && startUri.getScheme().equalsIgnoreCase(candidate.getScheme());
    }

    private ExclusionDecision exclusionDecision(URI uri, CrawlOptions options) {
        String path = normalizedPath(uri).toLowerCase(Locale.ROOT);
        if (EXCLUDED_EXTENSIONS.stream().anyMatch(path::endsWith)) {
            boolean attachment = isAttachment(path);
            if (attachment && options.includeAttachments()) {
                return new ExclusionDecision(true, true, "ATTACHMENT_QUEUED", "Queued supported attachment for extraction.");
            }
            return new ExclusionDecision(true, attachment, attachment ? "ATTACHMENT_DISABLED" : "EXTENSION_EXCLUDED",
                    attachment ? "Skipped attachment because attachment collection is disabled." : "Skipped URL because extension is excluded.");
        }
        if (EXCLUDED_PATH_PARTS.stream().anyMatch(path::contains)) {
            if (options.includeAttachments() && isLikelyAttachmentPath(path)) {
                return new ExclusionDecision(false, true, null, "");
            }
            return new ExclusionDecision(true, false, "PATH_EXCLUDED", "Skipped URL because path is excluded.");
        }
        String query = uri.getRawQuery();
        if (query != null && query.toLowerCase(Locale.ROOT).matches(".*(^|&)(q|query|search|replytocom|share)=.*")) {
            return new ExclusionDecision(true, false, "QUERY_EXCLUDED", "Skipped URL because query parameters are excluded.");
        }
        return new ExclusionDecision(false, false, null, "");
    }

    private boolean isAttachment(String path) {
        return path.endsWith(".doc") || path.endsWith(".docx") || path.endsWith(".pdf")
                || path.endsWith(".ppt") || path.endsWith(".pptx")
                || path.endsWith(".xls") || path.endsWith(".xlsx")
                || path.endsWith(".csv") || path.endsWith(".txt") || path.endsWith(".md") || path.endsWith(".markdown");
    }

    private boolean isLikelyAttachmentPath(String path) {
        return path.contains("/attachment") || path.contains("/attachments") || path.contains("/download") || path.contains("/uploads");
    }

    private String siteDomain(String host) {
        String[] parts = host == null ? new String[0] : host.toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length < 2) {
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private URI normalizeUri(URI uri) {
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = requireHost(uri);
        String authority = uri.getRawUserInfo() == null ? host : uri.getRawUserInfo() + "@" + host;
        if (uri.getPort() >= 0) {
            authority = authority + ":" + uri.getPort();
        }
        String path = normalizedPath(uri);
        String query = uri.getRawQuery();
        try {
            return new URI(scheme, authority, path, query, null).normalize();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("잘못된 URL 형식입니다: " + uri, ex);
        }
    }

    private String normalizedPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String scopePath(URI startUri) {
        String path = normalizedPath(startUri);
        if ("/".equals(path) || path.endsWith("/")) {
            return path;
        }
        int slash = path.lastIndexOf('/');
        String lastSegment = slash >= 0 ? path.substring(slash + 1) : path;
        if (lastSegment.contains(".") && slash > 0) {
            return path.substring(0, slash);
        }
        return path;
    }

    private String requireHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL에는 도메인 또는 호스트가 필요합니다. 예: https://example.com/docs");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    public record CrawlResult(List<ExtractedDocument> documents, int fetchedCount, int skippedCount) {
    }

    private record ScopeDecision(boolean allowed, String reasonCode, String message) {
    }

    private record ExclusionDecision(boolean excluded, boolean attachment, String reasonCode, String message) {
    }

    private record CrawlUrl(URI uri, int depth, URI referrer) {
    }

    private record SkipDecision(String reasonCode, String message, Map<String, Object> metadata) {
    }
}
