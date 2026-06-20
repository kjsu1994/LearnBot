package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.repository.DocumentRepository;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebPageExtractor {
    private static final int MAX_WEB_PREVIEW_BLOCKS = 220;
    private static final int MAX_WEB_BLOCK_CHARS = 1_200;
    private static final int MAX_WEB_LIST_ITEMS = 40;
    private static final int MAX_WEB_TABLE_ROWS = 60;
    private static final int MAX_WEB_TABLE_COLUMNS = 8;
    private static final int MAX_WEB_LINKS = 80;
    private static final int MAX_WEB_IMAGES = 40;
    private static final Set<String> ATTACHMENT_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/msword",
            "application/vnd.ms-excel",
            "text/csv",
            "text/plain",
            "text/markdown"
    );

    private final LearnBotProperties properties;
    private final AdminSettingsService adminSettingsService;
    private final DocumentRepository repository;
    private final FileExtractor fileExtractor;
    private final ConcurrentMap<String, Long> lastFetchByHost = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RobotsRules> robotsCache = new ConcurrentHashMap<>();

    @Autowired
    public WebPageExtractor(LearnBotProperties properties, AdminSettingsService adminSettingsService, DocumentRepository repository, FileExtractor fileExtractor) {
        this.properties = properties;
        this.adminSettingsService = adminSettingsService;
        this.repository = repository;
        this.fileExtractor = fileExtractor;
    }

    WebPageExtractor(LearnBotProperties properties, AdminSettingsService adminSettingsService, DocumentRepository repository) {
        this(properties, adminSettingsService, repository, new FileExtractor());
    }

    public ExtractedDocument extract(String url) {
        return extract(null, url);
    }

    public ExtractedDocument extract(UUID sourceId, String url) {
        return extract(sourceId, url, CrawlOptions.defaults());
    }

    public ExtractedDocument extract(UUID sourceId, String url, CrawlOptions options) {
        return fetchPage(sourceId, url, options).document();
    }

    public FetchedPage fetchPage(UUID sourceId, String url) {
        return fetchPage(sourceId, url, CrawlOptions.defaults());
    }

    public FetchedPage fetchPage(UUID sourceId, String url, CrawlOptions options) {
        CrawlOptions safeOptions = options == null ? CrawlOptions.defaults() : options.normalized();
        URI uri = URI.create(url);
        requireHttpScheme(uri);
        String host = requireHost(uri);
        boolean allowedDomain = isAllowedDomain(host);
        if (!allowedDomain) {
            audit(new CrawlAuditEvent(sourceId, url, host, false, null, null, false,
                    "DOMAIN_NOT_ALLOWED", null, null, url, null, Map.of(), "Domain is not allowed: " + host));
            throw new IllegalArgumentException("Domain is not allowed: " + host);
        }

        Boolean robotsAllowed = null;
        if (adminSettingsService.isRespectRobotsTxt() && safeOptions.robotsFailurePolicy() != RobotsFailurePolicy.IGNORE) {
            RobotsDecision robotsDecision = robotsAllows(uri, safeOptions.robotsFailurePolicy());
            robotsAllowed = robotsDecision.allowed();
            if (!robotsDecision.allowed()) {
                audit(new CrawlAuditEvent(sourceId, url, host, true, false, null, false,
                        robotsDecision.reasonCode(), null, null, url, null, Map.of("robotsMessage", robotsDecision.message()),
                        robotsDecision.message()));
                throw new IllegalArgumentException("robots.txt disallows URL: " + url);
            }
            if (robotsDecision.reasonCode() != null && robotsDecision.reasonCode().startsWith("ROBOTS_ALLOWED_BY")) {
                audit(new CrawlAuditEvent(sourceId, url, host, true, true, null, false,
                        robotsDecision.reasonCode(), null, null, url, null, Map.of("robotsMessage", robotsDecision.message()),
                        robotsDecision.message()));
            }
        } else if (safeOptions.robotsFailurePolicy() == RobotsFailurePolicy.IGNORE) {
            robotsAllowed = true;
        }

        try {
            if (safeOptions.renderMode() == WebRenderMode.PLAYWRIGHT_ALWAYS) {
                audit(new CrawlAuditEvent(sourceId, url, host, true, robotsAllowed, null, false,
                        "PLAYWRIGHT_UNAVAILABLE_STATIC_FALLBACK", null, null, url, null, Map.of("renderMode", safeOptions.renderMode().name()),
                        "Playwright rendering is not installed in this runtime; falling back to static HTML fetch."));
            }
            waitForRateLimit(host);
            Connection.Response response = Jsoup.connect(url)
                    .timeout(properties.getCrawler().getTimeoutSeconds() * 1000)
                    .userAgent("LearnBot/0.1")
                    .ignoreContentType(true)
                    .execute();
            int statusCode = response.statusCode();
            String responseContentType = cleanContentType(response.contentType());
            if (!isHtml(responseContentType)) {
                throw new IllegalArgumentException("URL did not return HTML content: " + responseContentType);
            }
            Document doc = response.parse();
            doc.setBaseUri(url);
            List<URI> links = extractLinks(doc);

            String canonicalUrl = canonicalUrl(doc, url);
            String heading = firstHeading(doc);
            String title = doc.title() == null || doc.title().isBlank() ? url : doc.title();
            String description = metaContent(doc, "meta[name=description]");
            String ogTitle = metaContent(doc, "meta[property=og:title]");
            String ogDescription = metaContent(doc, "meta[property=og:description]");
            String language = firstNonBlank(doc.select("html").attr("lang"), metaContent(doc, "meta[http-equiv=content-language]"));
            List<String> headings = doc.select("h1, h2, h3").stream()
                    .map(Element::text)
                    .map(this::cleanText)
                    .filter(value -> !value.isBlank())
                    .limit(30)
                    .toList();

            sanitizeDocument(doc);
            String bodyText = doc.body() == null ? "" : doc.body().text();
            RootSelection rootSelection = previewRootSelection(doc);
            List<Map<String, Object>> previewBlocks = webPreviewBlocks(doc);
            String structuredText = webPreviewText(previewBlocks);
            String content = webContentHeader(title, url, heading) + (structuredText.isBlank() ? bodyText : structuredText);
            List<String> images = doc.select("img[src]").stream()
                    .map(element -> firstNonBlank(element.attr("alt"), element.absUrl("src")))
                    .filter(value -> !value.isBlank())
                    .limit(MAX_WEB_IMAGES)
                    .toList();

            if (!images.isEmpty()) {
                content = content + "\n\nImages:\n" + String.join("\n", images);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("host", uri.getHost());
            metadata.put("canonicalUrl", canonicalUrl);
            metadata.put("finalUrl", response.url() == null ? url : response.url().toString());
            metadata.put("title", title);
            metadata.put("description", description);
            metadata.put("ogTitle", ogTitle);
            metadata.put("ogDescription", ogDescription);
            metadata.put("language", language);
            metadata.put("heading", heading);
            metadata.put("headings", headings);
            metadata.put("bodyTextLength", bodyText.length());
            metadata.put("selectorUsed", rootSelection.selector());
            metadata.put("extractionStrategy", rootSelection.strategy());
            metadata.put("contentType", responseContentType);
            metadata.put("outboundLinkCount", links.size());
            metadata.put("imageCount", images.size());
            metadata.put("renderMode", safeOptions.renderMode().name());
            metadata.put("webPreviewVersion", 1);
            metadata.put("webPreviewBlocks", previewBlocks);
            metadata.put("webPreviewLinks", webLinks(doc));

            ExtractedDocument extracted = new ExtractedDocument(
                    title,
                    url,
                    "text/html",
                    content,
                    metadata
            );
            audit(new CrawlAuditEvent(sourceId, url, host, true, robotsAllowed, statusCode, true,
                    "FETCHED", null, null, response.url() == null ? url : response.url().toString(), responseContentType,
                    Map.of("bodyTextLength", bodyText.length(), "renderMode", safeOptions.renderMode().name()),
                    "Fetched page."));
            return new FetchedPage(uri, extracted, links, host, robotsAllowed, statusCode);
        } catch (Exception ex) {
            audit(new CrawlAuditEvent(sourceId, url, host, true, robotsAllowed, null, false,
                    "FETCH_ERROR", null, null, url, null, Map.of(), ex.getMessage()));
            throw new IllegalArgumentException("Could not crawl URL: " + ex.getMessage(), ex);
        }
    }

    public void auditSkipped(UUID sourceId, URI uri, String message) {
        auditSkipped(sourceId, uri, "SKIPPED", null, null, null, message, Map.of());
    }

    public void auditSkipped(
            UUID sourceId,
            URI uri,
            String reasonCode,
            Integer depth,
            String referrerUrl,
            String contentType,
            String message,
            Map<String, Object> metadata
    ) {
        String host = safeHost(uri);
        audit(new CrawlAuditEvent(sourceId, uri.toString(), host, true, null, null, false,
                reasonCode, depth, referrerUrl, uri.toString(), contentType, metadata == null ? Map.of() : metadata, message));
    }

    public ExtractedDocument fetchAttachment(UUID sourceId, URI uri, String referrerUrl, CrawlOptions options) {
        String url = uri.toString();
        String host = requireHost(uri);
        if (!isAllowedDomain(host)) {
            auditSkipped(sourceId, uri, "DOMAIN_NOT_ALLOWED", null, referrerUrl, null,
                    "Domain is not allowed: " + host, Map.of());
            return null;
        }
        try {
            waitForRateLimit(host);
            Connection.Response response = Jsoup.connect(url)
                    .timeout(properties.getCrawler().getTimeoutSeconds() * 1000)
                    .userAgent("LearnBot/0.1")
                    .ignoreContentType(true)
                    .execute();
            String contentType = cleanContentType(response.contentType());
            byte[] body = response.bodyAsBytes();
            if (!isAllowedAttachment(contentType, url)) {
                auditSkipped(sourceId, uri, "ATTACHMENT_UNSUPPORTED_TYPE", null, referrerUrl, contentType,
                        "Skipped attachment because content type is not supported: " + contentType, Map.of());
                return null;
            }
            if (body.length > properties.getCrawler().getMaxAttachmentBytes()) {
                auditSkipped(sourceId, uri, "ATTACHMENT_TOO_LARGE", null, referrerUrl, contentType,
                        "Skipped attachment because it exceeds the configured byte limit: " + body.length,
                        Map.of("sizeBytes", body.length, "maxBytes", properties.getCrawler().getMaxAttachmentBytes()));
                return null;
            }
            String filename = attachmentFilename(uri, contentType);
            ExtractedDocument document = fileExtractor.extract(filename, url, contentType, body, Map.of(
                    "parentPageUrl", referrerUrl == null ? "" : referrerUrl,
                    "attachmentUrl", url,
                    "attachmentContentType", contentType,
                    "attachmentSizeBytes", body.length
            ));
            audit(new CrawlAuditEvent(sourceId, url, host, true, null, response.statusCode(), true,
                    "ATTACHMENT_FETCHED", null, referrerUrl, response.url() == null ? url : response.url().toString(), contentType,
                    Map.of("sizeBytes", body.length), "Fetched attachment."));
            return document;
        } catch (RuntimeException ex) {
            auditSkipped(sourceId, uri, "ATTACHMENT_FETCH_ERROR", null, referrerUrl, null, ex.getMessage(), Map.of());
            return null;
        } catch (Exception ex) {
            auditSkipped(sourceId, uri, "ATTACHMENT_FETCH_ERROR", null, referrerUrl, null, ex.getMessage(), Map.of());
            return null;
        }
    }

    private void requireHttpScheme(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("URL은 http 또는 https로 시작해야 합니다.");
        }
    }

    private String requireHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL에 도메인 또는 호스트가 필요합니다. 예: https://example.com/docs");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private String safeHost(URI uri) {
        String host = uri == null ? null : uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    private List<URI> extractLinks(Document doc) {
        return doc.select("a[href]").stream()
                .map(element -> element.absUrl("href"))
                .filter(value -> !value.isBlank())
                .map(this::toUriOrNull)
                .filter(uri -> uri != null)
                .toList();
    }

    private URI toUriOrNull(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String canonicalUrl(Document doc, String fallbackUrl) {
        var canonical = doc.selectFirst("link[rel=canonical]");
        if (canonical == null) {
            return fallbackUrl;
        }
        String href = canonical.absUrl("href");
        return href == null || href.isBlank() ? fallbackUrl : href;
    }

    private String metaContent(Document doc, String selector) {
        Element element = doc.selectFirst(selector);
        return element == null ? "" : cleanText(element.attr("content"));
    }

    private String firstHeading(Document doc) {
        var heading = doc.selectFirst("h1, h2");
        return heading == null ? "" : heading.text();
    }

    private String webContentHeader(String title, String url, String heading) {
        StringBuilder builder = new StringBuilder();
        builder.append("Page title: ").append(title).append("\n");
        builder.append("URL: ").append(url).append("\n");
        if (heading != null && !heading.isBlank()) {
            builder.append("Heading: ").append(heading).append("\n");
        }
        builder.append("\n");
        return builder.toString();
    }

    private void sanitizeDocument(Document doc) {
        doc.select("script, style, noscript, svg, canvas, nav, footer, iframe").remove();
        doc.select("input[type=password], input[type=hidden], input[type=submit], input[type=button], button").remove();
        doc.select("[class*=captcha], [id*=captcha], [aria-hidden=true]").remove();
    }

    private List<Map<String, Object>> webPreviewBlocks(Document doc) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Element root = previewRootSelection(doc).root();
        if (root == null) {
            return blocks;
        }
        for (Element child : root.children()) {
            collectPreviewBlocks(child, blocks);
            if (blocks.size() >= MAX_WEB_PREVIEW_BLOCKS) {
                break;
            }
        }
        return blocks;
    }

    private RootSelection previewRootSelection(Document doc) {
        Element body = doc.body();
        if (body == null) {
            return new RootSelection(null, "", "empty-body");
        }
        String selector = "main, article, [role=main], .docs-content, .document, .content, .contents, "
                + "#content, #main, .markdown-body, .post, .entry-content, #app";
        Element main = doc.selectFirst(selector);
        if (main != null && cleanText(main.text()).length() >= Math.min(120, properties.getCrawler().getMinContentChars())) {
            return new RootSelection(main, selectorFor(main), "selector");
        }
        Element dense = densestTextElement(body).orElse(body);
        String strategy = dense == body ? "body-fallback" : "density";
        return new RootSelection(dense, selectorFor(dense), strategy);
    }

    private Optional<Element> densestTextElement(Element root) {
        return root.select("main, article, section, div").stream()
                .filter(element -> element.children().size() <= 80)
                .max(Comparator.comparingInt(element -> cleanText(element.text()).length()));
    }

    private String selectorFor(Element element) {
        if (element == null) {
            return "";
        }
        if (!element.id().isBlank()) {
            return "#" + element.id();
        }
        if (!element.className().isBlank()) {
            return element.normalName() + "." + element.classNames().stream().findFirst().orElse("");
        }
        return element.normalName();
    }

    private void collectPreviewBlocks(Element element, List<Map<String, Object>> blocks) {
        if (blocks.size() >= MAX_WEB_PREVIEW_BLOCKS) {
            return;
        }
        String tag = element.normalName();
        if (tag.matches("h[1-6]")) {
            addTextBlock(blocks, "heading", cleanText(element.text()), headingLevel(tag));
            return;
        }
        if ("p".equals(tag)) {
            addTextBlock(blocks, "paragraph", cleanText(element.text()), null);
            return;
        }
        if ("pre".equals(tag)) {
            addTextBlock(blocks, "code", trimToLimit(element.text(), MAX_WEB_BLOCK_CHARS * 2), null);
            return;
        }
        if ("blockquote".equals(tag)) {
            addTextBlock(blocks, "quote", cleanText(element.text()), null);
            return;
        }
        if ("ul".equals(tag) || "ol".equals(tag)) {
            addListBlock(blocks, element);
            return;
        }
        if ("table".equals(tag)) {
            addTableBlock(blocks, element);
            return;
        }
        if ("img".equals(tag)) {
            addImageBlock(blocks, element);
            return;
        }
        if (element.children().isEmpty()) {
            addTextBlock(blocks, "paragraph", cleanText(element.text()), null);
            return;
        }
        for (Element child : element.children()) {
            collectPreviewBlocks(child, blocks);
            if (blocks.size() >= MAX_WEB_PREVIEW_BLOCKS) {
                break;
            }
        }
    }

    private void addTextBlock(List<Map<String, Object>> blocks, String type, String text, Integer level) {
        String clean = trimToLimit(text, MAX_WEB_BLOCK_CHARS);
        if (clean.isBlank() || isDuplicateTail(blocks, clean)) {
            return;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        if (level != null) {
            block.put("level", level);
        }
        block.put("text", clean);
        blocks.add(block);
    }

    private void addListBlock(List<Map<String, Object>> blocks, Element element) {
        List<String> items = element.children().stream()
                .filter(child -> "li".equals(child.normalName()))
                .map(child -> cleanText(child.text()))
                .filter(value -> !value.isBlank())
                .limit(MAX_WEB_LIST_ITEMS)
                .toList();
        if (items.isEmpty()) {
            return;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "list");
        block.put("items", items);
        blocks.add(block);
    }

    private void addTableBlock(List<Map<String, Object>> blocks, Element element) {
        List<List<String>> rows = new ArrayList<>();
        for (Element row : element.select("tr")) {
            if (rows.size() >= MAX_WEB_TABLE_ROWS) {
                break;
            }
            List<String> cells = row.select("th, td").stream()
                    .map(cell -> trimToLimit(cleanText(cell.text()), MAX_WEB_BLOCK_CHARS / 4))
                    .limit(MAX_WEB_TABLE_COLUMNS)
                    .toList();
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "table");
        block.put("rows", rows);
        blocks.add(block);
    }

    private void addImageBlock(List<Map<String, Object>> blocks, Element element) {
        String href = element.absUrl("src");
        String text = firstNonBlank(element.attr("alt"), href);
        if (href.isBlank() && text.isBlank()) {
            return;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("text", trimToLimit(cleanText(text), 240));
        block.put("href", href);
        blocks.add(block);
    }

    private boolean isDuplicateTail(List<Map<String, Object>> blocks, String text) {
        if (blocks.isEmpty()) {
            return false;
        }
        Object previous = blocks.get(blocks.size() - 1).get("text");
        return text.equals(previous);
    }

    private int headingLevel(String tag) {
        try {
            return Integer.parseInt(tag.substring(1));
        } catch (RuntimeException ex) {
            return 2;
        }
    }

    private String webPreviewText(List<Map<String, Object>> blocks) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> block : blocks) {
            String type = String.valueOf(block.getOrDefault("type", ""));
            if ("list".equals(type)) {
                Object items = block.get("items");
                if (items instanceof List<?> list) {
                    for (Object item : list) {
                        appendPreviewLine(builder, "- " + item);
                    }
                }
            } else if ("table".equals(type)) {
                Object rows = block.get("rows");
                if (rows instanceof List<?> list) {
                    for (Object row : list) {
                        if (row instanceof List<?> cells) {
                            appendPreviewLine(builder, "| " + String.join(" | ", cells.stream().map(String::valueOf).toList()) + " |");
                        }
                    }
                }
            } else {
                Object text = block.get("text");
                appendPreviewLine(builder, text == null ? "" : String.valueOf(text));
            }
        }
        return builder.toString().trim();
    }

    private void appendPreviewLine(StringBuilder builder, String text) {
        String clean = cleanText(text);
        if (clean.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(clean);
    }

    private List<String> webLinks(Document doc) {
        return doc.select("a[href]").stream()
                .map(element -> {
                    String label = cleanText(element.text());
                    String href = element.absUrl("href");
                    if (href.isBlank()) {
                        return "";
                    }
                    return label.isBlank() ? href : label + " - " + href;
                })
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAX_WEB_LINKS)
                .toList();
    }

    private String cleanText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String trimToLimit(String value, int limit) {
        String clean = cleanText(value);
        return clean.length() <= limit ? clean : clean.substring(0, limit).trim() + "...";
    }

    private String firstNonBlank(String first, String second) {
        String cleanFirst = cleanText(first);
        if (!cleanFirst.isBlank()) {
            return cleanFirst;
        }
        return cleanText(second);
    }

    private String cleanContentType(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private boolean isHtml(String contentType) {
        return contentType == null || contentType.isBlank()
                || contentType.equals("text/html")
                || contentType.equals("application/xhtml+xml");
    }

    private boolean isAllowedAttachment(String contentType, String url) {
        if (ATTACHMENT_CONTENT_TYPES.contains(contentType)) {
            return true;
        }
        String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".pdf") || path.endsWith(".docx") || path.endsWith(".pptx")
                || path.endsWith(".xlsx") || path.endsWith(".xls") || path.endsWith(".csv")
                || path.endsWith(".txt") || path.endsWith(".md") || path.endsWith(".markdown");
    }

    private String attachmentFilename(URI uri, String contentType) {
        String path = uri.getPath();
        String name = path == null || path.isBlank() ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (!name.isBlank() && name.contains(".")) {
            return name;
        }
        return "web-attachment" + extensionFor(contentType);
    }

    private String extensionFor(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "text/csv" -> ".csv";
            case "text/markdown" -> ".md";
            default -> ".txt";
        };
    }

    private boolean isAllowedDomain(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return adminSettingsService.allowedDomains().stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT).trim())
                .filter(domain -> !domain.isBlank())
                .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
    }

    private void waitForRateLimit(String host) {
        long delay = properties.getCrawler().getRateLimitMillis();
        if (delay <= 0) {
            return;
        }
        synchronized (lastFetchByHost) {
            long now = System.currentTimeMillis();
            long lastFetch = lastFetchByHost.getOrDefault(host, 0L);
            long wait = delay - (now - lastFetch);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalArgumentException("Crawler rate limit wait was interrupted.", ex);
                }
            }
            lastFetchByHost.put(host, System.currentTimeMillis());
        }
    }

    private RobotsDecision robotsAllows(URI uri, RobotsFailurePolicy policy) {
        RobotsRules rules = robotsCache.computeIfAbsent(robotsKey(uri), ignored -> fetchRobotsRules(uri));
        if (rules.fetchFailed()) {
            if (policy == RobotsFailurePolicy.ALLOW_ON_ERROR) {
                return new RobotsDecision(true, "ROBOTS_ALLOWED_BY_FAILURE_POLICY", "robots.txt could not be fetched; allowed by policy.");
            }
            return new RobotsDecision(false, "ROBOTS_FETCH_FAILED", rules.failureMessage());
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            path = path + "?" + uri.getRawQuery();
        }
        boolean allowed = rules.allows(path);
        return new RobotsDecision(allowed, allowed ? null : "ROBOTS_DISALLOW", allowed ? "robots.txt allows URL." : "robots.txt disallows URL.");
    }

    private RobotsRules fetchRobotsRules(URI uri) {
        String robotsUrl = uri.getScheme() + "://" + uri.getAuthority() + "/robots.txt";
        try {
            Connection.Response response = Jsoup.connect(robotsUrl)
                    .timeout(properties.getCrawler().getTimeoutSeconds() * 1000)
                    .userAgent("LearnBot/0.1")
                    .ignoreHttpErrors(true)
                    .execute();
            if (response.statusCode() == 404 || response.statusCode() == 410) {
                return RobotsRules.allowAll();
            }
            if (response.statusCode() >= 400) {
                return RobotsRules.fetchFailed("robots.txt returned HTTP " + response.statusCode());
            }
            return RobotsRules.parse(response.body());
        } catch (Exception ex) {
            return RobotsRules.fetchFailed("robots.txt fetch failed: " + ex.getMessage());
        }
    }

    private String robotsKey(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private void audit(CrawlAuditEvent event) {
        repository.createCrawlAuditLog(event);
    }

    public record FetchedPage(
            URI uri,
            ExtractedDocument document,
            List<URI> links,
            String host,
            Boolean robotsAllowed,
            Integer statusCode
    ) {
    }

    public List<URI> sitemapUrls(URI startUri) {
        List<String> sitemapLocations = new ArrayList<>();
        RobotsRules rules = robotsCache.computeIfAbsent(robotsKey(startUri), ignored -> fetchRobotsRules(startUri));
        sitemapLocations.addAll(rules.sitemaps());
        sitemapLocations.add(startUri.getScheme() + "://" + startUri.getAuthority() + "/sitemap.xml");
        return sitemapLocations.stream()
                .distinct()
                .flatMap(location -> fetchSitemap(location, 0).stream())
                .distinct()
                .toList();
    }

    private List<URI> fetchSitemap(String sitemapUrl, int depth) {
        if (depth > 2) {
            return List.of();
        }
        try {
            Connection.Response response = Jsoup.connect(sitemapUrl)
                    .timeout(properties.getCrawler().getTimeoutSeconds() * 1000)
                    .userAgent("LearnBot/0.1")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .execute();
            if (response.statusCode() >= 400) {
                return List.of();
            }
            Document xml = Jsoup.parse(response.body(), sitemapUrl, org.jsoup.parser.Parser.xmlParser());
            List<URI> urls = new ArrayList<>();
            for (Element sitemap : xml.select("sitemap > loc")) {
                urls.addAll(fetchSitemap(sitemap.text(), depth + 1));
            }
            for (Element loc : xml.select("url > loc")) {
                URI uri = toUriOrNull(loc.text());
                if (uri != null) {
                    urls.add(uri);
                }
            }
            return urls;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private record RootSelection(Element root, String selector, String strategy) {
    }

    private record RobotsDecision(boolean allowed, String reasonCode, String message) {
    }

    private record RobotsRule(String path, boolean allow) {
    }

    private record RobotsRules(List<RobotsRule> rules, boolean fetchFailed, String failureMessage, List<String> sitemaps) {
        static RobotsRules allowAll() {
            return new RobotsRules(List.of(), false, "", List.of());
        }

        static RobotsRules fetchFailed(String message) {
            return new RobotsRules(List.of(), true, message, List.of());
        }

        static RobotsRules parse(String body) {
            List<RobotsRule> rules = new ArrayList<>();
            List<String> sitemaps = new ArrayList<>();
            boolean applies = false;
            boolean sawRule = false;

            for (String rawLine : body.split("\\R")) {
                String line = rawLine.split("#", 2)[0].trim();
                if (line.isBlank() || !line.contains(":")) {
                    continue;
                }
                String name = line.substring(0, line.indexOf(':')).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(line.indexOf(':') + 1).trim();

                if ("user-agent".equals(name)) {
                    if (sawRule) {
                        applies = false;
                        sawRule = false;
                    }
                    String agent = value.toLowerCase(Locale.ROOT);
                    if ("*".equals(agent) || "learnbot".equals(agent)) {
                        applies = true;
                    }
                    continue;
                }

                if ("sitemap".equals(name) && !value.isBlank()) {
                    sitemaps.add(value);
                    continue;
                }

                if (!applies || (!"allow".equals(name) && !"disallow".equals(name))) {
                    continue;
                }

                sawRule = true;
                if (value.isBlank()) {
                    continue;
                }
                rules.add(new RobotsRule(normalizeRobotPath(value), "allow".equals(name)));
            }

            return new RobotsRules(rules, false, "", sitemaps);
        }

        boolean allows(String path) {
            return rules.stream()
                    .filter(rule -> path.startsWith(rule.path()))
                    .max(Comparator.comparingInt(rule -> rule.path().length()))
                    .map(RobotsRule::allow)
                    .orElse(true);
        }

        private static String normalizeRobotPath(String value) {
            String clean = value.trim();
            int wildcard = clean.indexOf('*');
            if (wildcard >= 0) {
                clean = clean.substring(0, wildcard);
            }
            int end = clean.indexOf('$');
            if (end >= 0) {
                clean = clean.substring(0, end);
            }
            return clean.isBlank() ? "/" : clean;
        }
    }
}
