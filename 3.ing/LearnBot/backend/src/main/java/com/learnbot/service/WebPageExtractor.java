package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.repository.DocumentRepository;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WebPageExtractor {
    private final LearnBotProperties properties;
    private final DocumentRepository repository;
    private final ConcurrentMap<String, Long> lastFetchByHost = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RobotsRules> robotsCache = new ConcurrentHashMap<>();

    public WebPageExtractor(LearnBotProperties properties, DocumentRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public ExtractedDocument extract(String url) {
        return extract(null, url);
    }

    public ExtractedDocument extract(UUID sourceId, String url) {
        URI uri = URI.create(url);
        String host = requireHost(uri);
        boolean allowedDomain = isAllowedDomain(host);
        if (!allowedDomain) {
            audit(sourceId, url, host, false, null, null, false, "Domain is not allowed: " + host);
            throw new IllegalArgumentException("Domain is not allowed: " + host);
        }

        Boolean robotsAllowed = null;
        if (properties.getCrawler().isRespectRobotsTxt()) {
            robotsAllowed = robotsAllows(uri);
            if (!robotsAllowed) {
                audit(sourceId, url, host, true, false, null, false, "robots.txt disallows URL.");
                throw new IllegalArgumentException("robots.txt disallows URL: " + url);
            }
        }

        try {
            waitForRateLimit(host);
            Connection.Response response = Jsoup.connect(url)
                    .timeout(properties.getCrawler().getTimeoutSeconds() * 1000)
                    .userAgent("LearnBot/0.1")
                    .execute();
            int statusCode = response.statusCode();
            Document doc = response.parse();

            doc.select("script, style, noscript, svg, canvas").remove();
            String title = doc.title() == null || doc.title().isBlank() ? url : doc.title();
            String content = doc.body() == null ? "" : doc.body().text();
            String images = doc.select("img[src]").stream()
                    .map(element -> element.absUrl("src"))
                    .filter(value -> !value.isBlank())
                    .limit(50)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");

            if (!images.isBlank()) {
                content = content + "\n\nImage URLs:\n" + images;
            }

            ExtractedDocument extracted = new ExtractedDocument(
                    title,
                    url,
                    "text/html",
                    content,
                    Map.of("host", uri.getHost())
            );
            audit(sourceId, url, host, true, robotsAllowed, statusCode, true,
                    "Fetched one page. Page limit per request: " + properties.getCrawler().getMaxPagesPerRequest());
            return extracted;
        } catch (Exception ex) {
            audit(sourceId, url, host, true, robotsAllowed, null, false, ex.getMessage());
            throw new IllegalArgumentException("Could not crawl URL: " + ex.getMessage(), ex);
        }
    }

    private String requireHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must include a host.");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private boolean isAllowedDomain(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return properties.getCrawler().getAllowedDomains().stream()
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

    private boolean robotsAllows(URI uri) {
        RobotsRules rules = robotsCache.computeIfAbsent(robotsKey(uri), ignored -> fetchRobotsRules(uri));
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            path = path + "?" + uri.getRawQuery();
        }
        return rules.allows(path);
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
                return RobotsRules.disallowAll();
            }
            return RobotsRules.parse(response.body());
        } catch (Exception ex) {
            return RobotsRules.disallowAll();
        }
    }

    private String robotsKey(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private void audit(
            UUID sourceId,
            String url,
            String host,
            boolean allowedDomain,
            Boolean robotsAllowed,
            Integer statusCode,
            boolean success,
            String message
    ) {
        repository.createCrawlAuditLog(sourceId, url, host, allowedDomain, robotsAllowed, statusCode, success, message);
    }

    private record RobotsRule(String path, boolean allow) {
    }

    private record RobotsRules(List<RobotsRule> rules) {
        static RobotsRules allowAll() {
            return new RobotsRules(List.of());
        }

        static RobotsRules disallowAll() {
            return new RobotsRules(List.of(new RobotsRule("/", false)));
        }

        static RobotsRules parse(String body) {
            List<RobotsRule> rules = new ArrayList<>();
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

                if (!applies || (!"allow".equals(name) && !"disallow".equals(name))) {
                    continue;
                }

                sawRule = true;
                if (value.isBlank()) {
                    continue;
                }
                rules.add(new RobotsRule(normalizeRobotPath(value), "allow".equals(name)));
            }

            return new RobotsRules(rules);
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
