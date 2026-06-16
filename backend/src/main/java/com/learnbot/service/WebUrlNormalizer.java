package com.learnbot.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Component
public class WebUrlNormalizer {
    public String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("웹 URL을 입력하세요.");
        }
        String clean = value.trim();
        String candidate = clean.contains("://") ? clean : "https://" + clean;
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException ex) {
            if (candidate.matches("(?i)^https?://$")) {
                throw new IllegalArgumentException("URL에 도메인 또는 호스트가 필요합니다. 예: https://example.com/docs", ex);
            }
            throw new IllegalArgumentException("URL 형식이 올바르지 않습니다. 예: https://example.com/docs", ex);
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("URL은 http 또는 https로 시작해야 합니다.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL에 도메인 또는 호스트가 필요합니다. 예: https://example.com/docs");
        }

        try {
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            return new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    uri.getRawUserInfo(),
                    host.toLowerCase(Locale.ROOT),
                    uri.getPort(),
                    path,
                    uri.getRawQuery(),
                    null
            ).normalize().toString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("URL 형식이 올바르지 않습니다. 예: https://example.com/docs", ex);
        }
    }
}
