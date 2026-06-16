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
        while (!queue.isEmpty() && fetchedCount < safeMaxPages) {
            CrawlUrl current = queue.removeFirst();
            if (!isInScope(startUri, rootHost, scopePath, current.uri())) {
                continue;
            }

            WebPageExtractor.FetchedPage page;
            try {
                page = extractor.fetchPage(sourceId, current.uri().toString());
                fetchedCount++;
            } catch (RuntimeException ex) {
                skippedCount++;
                continue;
            }

            for (URI link : page.links()) {
                if (current.depth() < safeDepth) {
                    enqueueIfCrawlable(queue, queuedOrVisited, startUri, rootHost, scopePath, link, current.depth() + 1);
                }
            }

            String skipReason = skipReason(page.document(), contentSignatures);
            if (skipReason != null) {
                skippedCount++;
                extractor.auditSkipped(sourceId, page.uri(), skipReason);
                continue;
            }
            documents.add(withCrawlMetadata(page.document(), startUri, current.depth()));
        }

        return new CrawlResult(documents, fetchedCount, skippedCount);
    }

    private void enqueueIfCrawlable(
            ArrayDeque<CrawlUrl> queue,
            Set<String> queuedOrVisited,
            URI startUri,
            String rootHost,
            String scopePath,
            URI link,
            int depth
    ) {
        URI normalized = normalizeUri(link);
        if (!isInScope(startUri, rootHost, scopePath, normalized) || isExcluded(normalized)) {
            return;
        }
        enqueue(queue, queuedOrVisited, normalized, depth);
    }

    private void enqueue(ArrayDeque<CrawlUrl> queue, Set<String> queuedOrVisited, URI uri, int depth) {
        String key = uri.toString();
        if (queuedOrVisited.add(key)) {
            queue.add(new CrawlUrl(uri, depth));
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

    private ExtractedDocument withCrawlMetadata(ExtractedDocument document, URI rootUri, int depth) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (document.metadata() != null) {
            metadata.putAll(document.metadata());
        }
        metadata.put("rootUrl", rootUri.toString());
        metadata.put("crawlDepth", depth);
        return new ExtractedDocument(
                document.title(),
                document.sourceUri(),
                document.contentType(),
                document.content(),
                metadata
        );
    }

    private boolean isInScope(URI startUri, String rootHost, String scopePath, URI candidate) {
        if (!sameScheme(startUri, candidate)) {
            return false;
        }
        String candidateHost = candidate.getHost() == null ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
        if (!rootHost.equals(candidateHost)) {
            return false;
        }
        String path = normalizedPath(candidate);
        if ("/".equals(scopePath)) {
            return true;
        }
        return path.equals(scopePath) || path.startsWith(scopePath.endsWith("/") ? scopePath : scopePath + "/");
    }

    private boolean sameScheme(URI startUri, URI candidate) {
        return startUri.getScheme() != null
                && candidate.getScheme() != null
                && startUri.getScheme().equalsIgnoreCase(candidate.getScheme());
    }

    private boolean isExcluded(URI uri) {
        String path = normalizedPath(uri).toLowerCase(Locale.ROOT);
        if (EXCLUDED_EXTENSIONS.stream().anyMatch(path::endsWith)) {
            return true;
        }
        if (EXCLUDED_PATH_PARTS.stream().anyMatch(path::contains)) {
            return true;
        }
        String query = uri.getRawQuery();
        return query != null && query.toLowerCase(Locale.ROOT).matches(".*(^|&)(q|query|search|replytocom|share)=.*");
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
            throw new IllegalArgumentException("Invalid crawl URL: " + uri, ex);
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
            throw new IllegalArgumentException("URL must include a host.");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    public record CrawlResult(List<ExtractedDocument> documents, int fetchedCount, int skippedCount) {
    }

    private record CrawlUrl(URI uri, int depth) {
    }
}
