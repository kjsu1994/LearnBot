package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminSettingsResponse;
import com.learnbot.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AdminSettingsService {
    private static final String RESPECT_ROBOTS_TXT_KEY = "crawler.respectRobotsTxt";

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
        return new AdminSettingsResponse(isRespectRobotsTxt());
    }

    public boolean isRespectRobotsTxt() {
        return settingsRepository.findValue(RESPECT_ROBOTS_TXT_KEY)
                .map(Boolean::parseBoolean)
                .orElse(properties.getCrawler().isRespectRobotsTxt());
    }

    public AdminSettingsResponse update(AppUser actor, boolean respectRobotsTxt) {
        settingsRepository.upsertValue(RESPECT_ROBOTS_TXT_KEY, Boolean.toString(respectRobotsTxt), actor.id());
        auditService.log(
                actor,
                "ADMIN_SETTINGS_UPDATE",
                "CRAWLER",
                RESPECT_ROBOTS_TXT_KEY,
                null,
                respectRobotsTxt
                        ? "robots.txt 정책 준수를 켰습니다. 웹 인덱싱은 robots.txt 차단을 따릅니다."
                        : "robots.txt 정책 준수를 껐습니다. 웹 인덱싱은 robots.txt 차단이 있어도 진행됩니다.",
                Map.of("respectRobotsTxt", respectRobotsTxt)
        );
        return current();
    }
}
