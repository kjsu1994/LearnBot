package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminSettingsResponse;
import com.learnbot.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdminSettingsService {
    private static final String RESPECT_ROBOTS_TXT_KEY = "crawler.respectRobotsTxt";
    private static final String ALLOWED_DOMAINS_KEY = "crawler.allowedDomains";

    private final AppSettingsRepository settingsRepository;
    private final LearnBotProperties properties;
    private final AuditService auditService;

    public AdminSettingsService(
            AppSettingsRepository settingsRepository,
            LearnBotProperties properties,
            AuditService auditService
    ) {
        this.settingsRepository = settingsRepository;
        this.properties = properties;
        this.auditService = auditService;
    }

    public AdminSettingsResponse current() {
        return new AdminSettingsResponse(isRespectRobotsTxt(), allowedDomains());
    }

    public boolean isRespectRobotsTxt() {
        return settingsRepository.findValue(RESPECT_ROBOTS_TXT_KEY)
                .map(Boolean::parseBoolean)
                .orElse(properties.getCrawler().isRespectRobotsTxt());
    }

    public List<String> allowedDomains() {
        return settingsRepository.findValue(ALLOWED_DOMAINS_KEY)
                .map(this::parseAllowedDomainText)
                .filter(domains -> !domains.isEmpty())
                .orElseGet(() -> normalizeAllowedDomains(properties.getCrawler().getAllowedDomains()));
    }

    public AdminSettingsResponse update(AppUser actor, Boolean respectRobotsTxt, List<String> allowedDomains) {
        if (respectRobotsTxt == null && allowedDomains == null) {
            throw new IllegalArgumentException("변경할 관리자 설정이 없습니다.");
        }
        if (respectRobotsTxt != null) {
            settingsRepository.upsertValue(RESPECT_ROBOTS_TXT_KEY, Boolean.toString(respectRobotsTxt), actor.id());
        }
        if (allowedDomains != null) {
            List<String> cleanDomains = normalizeAllowedDomains(allowedDomains);
            if (cleanDomains.isEmpty()) {
                throw new IllegalArgumentException("허용 도메인 또는 URL을 1개 이상 입력하세요.");
            }
            settingsRepository.upsertValue(ALLOWED_DOMAINS_KEY, String.join("\n", cleanDomains), actor.id());
        }
        auditService.log(
                actor,
                "ADMIN_SETTINGS_UPDATE",
                "CRAWLER",
                "crawler",
                null,
                "웹 크롤링 관리자 설정을 변경했습니다.",
                Map.of(
                        "respectRobotsTxt", isRespectRobotsTxt(),
                        "allowedDomains", allowedDomains()
                )
        );
        return current();
    }

    private List<String> parseAllowedDomainText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return normalizeAllowedDomains(List.of(text.split("[,\\r\\n]+")));
    }

    private List<String> normalizeAllowedDomains(List<String> values) {
        Set<String> domains = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String domain = normalizeAllowedDomain(value);
            if (domain != null) {
                domains.add(domain);
            }
        }
        return new ArrayList<>(domains);
    }

    private String normalizeAllowedDomain(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.trim().toLowerCase(Locale.ROOT);
        try {
            URI uri = clean.contains("://") ? URI.create(clean) : URI.create("https://" + clean);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                clean = host;
            }
        } catch (IllegalArgumentException ignored) {
            int slash = clean.indexOf('/');
            if (slash >= 0) {
                clean = clean.substring(0, slash);
            }
        }
        if (clean.startsWith("*.")) {
            clean = clean.substring(2);
        }
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isBlank() || clean.contains(" ")) {
            throw new IllegalArgumentException("허용 도메인 형식이 올바르지 않습니다: " + value);
        }
        return clean;
    }
}
