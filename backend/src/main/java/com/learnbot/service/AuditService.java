package com.learnbot.service;

import com.learnbot.dto.AuditLogSummary;
import com.learnbot.repository.SecurityRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final SecurityRepository securityRepository;

    public AuditService(SecurityRepository securityRepository) {
        this.securityRepository = securityRepository;
    }

    public void log(AppUser actor, String action, String targetType, UUID targetId, UUID spaceId, String message) {
        log(actor, action, targetType, targetId == null ? null : targetId.toString(), spaceId, message, Map.of());
    }

    public void log(AppUser actor, String action, String targetType, String targetId, UUID spaceId, String message, Map<String, Object> metadata) {
        securityRepository.createAuditLog(
                actor == null ? null : actor.id(),
                action,
                targetType,
                targetId,
                spaceId,
                message,
                metadata
        );
    }

    public List<AuditLogSummary> list(Integer limit) {
        return securityRepository.listAuditLogs(limit);
    }
}

