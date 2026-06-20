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

        enqueue(queue, queuedOrVisited, startUri, 0);
        if (safeOptions.useSitemap()) {
            for (URI sitemapUri : extractor.sitemapUrls(startUri)) {
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
                    ExtractedDocument attachment = extractor.fetchAttachment(
                            sourceId,
                            current.uri(),
                            current.referrer() == null ? null : current.referrer().toString(),
                            safeOptions
                    );
                    if (attachment != null) {
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

            String skipReason = skipReason(page.document(), contentSignatures);
            if (skipReason != null) {
                skippedCount++;
                String reasonCode = skipReason.startsWith("Skipped page because extractable body text is too short")
                        ? "LOW_CONTENT"
                        : "DUPLICATE_CONTENT";
                extractor.auditSkipped(sourceId, page.uri(), reasonCode, current.depth(),
                        current.referrer() == null ? null : current.referrer().toString(), page.document().contentType(), skipReason, Map.of());
                continue;
            }
            documents.add(withCrawlMetadata(page.document(), startUri, current.depth(), current.referrer()));
        }

        return new CrawlResult(documents, fetchedCount, skippedCount);
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

    private String skipReason(ExtractedDocument document, Set<String> contentSignatures) {
        int bodyLength = bodyTextLength(document);
        if (bodyLength < properties.getCrawler().getMinContentChars()) {
            return "Skipped page because extractable body text is too short: " + bodyLength + " chars.";
        }
        String signature = contentSignature(document.content());
        if (!contentSignatures.add(signature)) {
            return "Skipped page because normalized text is duplicated.";
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
            throw new IllegalArgumentException("크롤링 URL 형식이 올바르지 않습니다: " + uri, ex);
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
            throw new IllegalArgumentException("URL에 도메인 또는 호스트가 필요합니다. 예: https://example.com/docs");
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
}
