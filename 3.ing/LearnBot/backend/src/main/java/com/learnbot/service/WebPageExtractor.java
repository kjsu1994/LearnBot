package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

@Component
public class WebPageExtractor {
    private final LearnBotProperties properties;

    public WebPageExtractor(LearnBotProperties properties) {
        this.properties = properties;
    }

    public ExtractedDocument extract(String url) {
        URI uri = URI.create(url);
        validateAllowedDomain(uri);

        try {
            Document doc = Jsoup.connect(url)
                    .timeout(properties.getCrawler().getTimeoutSeconds() * 1000)
                    .userAgent("LearnBot/0.1")
                    .get();

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

            return new ExtractedDocument(
                    title,
                    url,
                    "text/html",
                    content,
                    Map.of("host", uri.getHost())
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not crawl URL: " + ex.getMessage(), ex);
        }
    }

    private void validateAllowedDomain(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must include a host.");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = properties.getCrawler().getAllowedDomains().stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT).trim())
                .filter(domain -> !domain.isBlank())
                .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));

        if (!allowed) {
            throw new IllegalArgumentException("Domain is not allowed: " + host);
        }
    }
}
