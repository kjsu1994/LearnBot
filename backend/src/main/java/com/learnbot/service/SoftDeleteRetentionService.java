package com.learnbot.service;

import com.learnbot.repository.SecurityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class SoftDeleteRetentionService {
    private static final int RETENTION_DAYS = 7;

    private final SecurityRepository securityRepository;

    public SoftDeleteRetentionService(SecurityRepository securityRepository) {
        this.securityRepository = securityRepository;
    }

    @PostConstruct
    void purgeOnStartup() {
        purgeExpired();
    }

    @Scheduled(cron = "0 25 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpired() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETENTION_DAYS);
        securityRepository.purgeDeletedSpacesOlderThan(cutoff);
        securityRepository.purgeDeletedUsersOlderThan(cutoff);
    }
}
