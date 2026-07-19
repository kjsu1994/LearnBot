package com.learnbot.service;

import com.learnbot.dto.LocalAgentStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds Local Agent audit events from an explicit allow-list. Enrollment codes,
 * credentials, and workspace paths are intentionally not accepted by this API.
 */
@Service
public class LocalAgentAuditService {
    private static final LocalAgentAuditService NOOP = new LocalAgentAuditService(null);

    private final AuditService auditService;

    public LocalAgentAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    public static LocalAgentAuditService noop() {
        return NOOP;
    }

    public void enrollmentDecision(
            UUID actorUserId,
            UUID enrollmentId,
            UUID agentId,
            UUID installationId,
            String agentVersion,
            String decision
    ) {
        String result = "DENY".equals(decision) ? "DENIED" : "APPROVED";
        Map<String, Object> metadata = common(actorUserId, agentId, installationId, agentVersion);
        metadata.put("actorType", "USER");
        metadata.put("actorUserId", actorUserId.toString());
        metadata.put("enrollmentId", enrollmentId.toString());
        metadata.put("decision", decision);
        metadata.put("result", result);
        log(actorUserId, "LOCAL_AGENT_ENROLLMENT_" + result, "LOCAL_AGENT_ENROLLMENT",
                enrollmentId, "Local Agent enrollment was " + result.toLowerCase() + ".", metadata);
    }

    public void deviceSelected(UUID actorUserId, LocalAgentDevice device) {
        Map<String, Object> metadata = deviceMetadata(actorUserId, device);
        metadata.put("actorType", "USER");
        metadata.put("actorUserId", actorUserId.toString());
        metadata.put("result", "SELECTED");
        log(actorUserId, "LOCAL_AGENT_DEVICE_SELECTED", "LOCAL_AGENT_DEVICE", device.agentId(),
                "Local Agent device was selected as the default.", metadata);
    }

    public void deviceRevokedByUser(UUID actorUserId, LocalAgentDevice device) {
        Map<String, Object> metadata = deviceMetadata(actorUserId, device);
        metadata.put("actorType", "USER");
        metadata.put("actorUserId", actorUserId.toString());
        metadata.put("result", "REVOKED");
        log(actorUserId, "LOCAL_AGENT_DEVICE_REVOKED", "LOCAL_AGENT_DEVICE", device.agentId(),
                "Local Agent device access was revoked by the user.", metadata);
    }

    public void deviceSelfRevoked(LocalAgentToken actor, LocalAgentDevice device) {
        UUID installationId = device == null ? null : device.installationId();
        String agentVersion = device == null ? null : device.agentVersion();
        Map<String, Object> metadata = common(actor.userId(), actor.agentId(), installationId, agentVersion);
        metadata.put("actorType", "LOCAL_AGENT");
        metadata.put("actorAgentId", actor.agentId().toString());
        metadata.put("result", "SELF_REVOKED");
        log(null, "LOCAL_AGENT_DEVICE_SELF_REVOKED", "LOCAL_AGENT_DEVICE", actor.agentId(),
                "Local Agent device revoked its own access.", metadata);
    }

    public void credentialRotationInitiated(LocalAgentToken actor, LocalAgentCredentialRotation rotation) {
        Map<String, Object> metadata = common(actor.userId(), actor.agentId(), null, null);
        metadata.put("actorType", "LOCAL_AGENT");
        metadata.put("actorAgentId", actor.agentId().toString());
        metadata.put("result", "PENDING_CONFIRMATION");
        log(null, "LOCAL_AGENT_CREDENTIAL_ROTATION_INITIATED", "LOCAL_AGENT_CREDENTIAL_ROTATION",
                rotation.id(), "Local Agent credential rotation was initiated.", metadata);
    }

    public void credentialRotationConfirmed(LocalAgentCredentialRotation rotation, boolean alreadyConfirmed) {
        Map<String, Object> metadata = common(rotation.userId(), rotation.agentId(), null, null);
        metadata.put("actorType", "LOCAL_AGENT");
        metadata.put("actorAgentId", rotation.agentId().toString());
        metadata.put("result", alreadyConfirmed ? "ALREADY_CONFIRMED" : "CONFIRMED");
        log(null, "LOCAL_AGENT_CREDENTIAL_ROTATION_CONFIRMED", "LOCAL_AGENT_CREDENTIAL_ROTATION",
                rotation.id(), "Local Agent credential rotation was confirmed.", metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateRequiredDispatchBlocked(UUID actorUserId, UUID agentId, LocalAgentStatusResponse status) {
        Map<String, Object> metadata = common(actorUserId, agentId, null, status == null ? null : status.version());
        metadata.put("actorType", "USER");
        metadata.put("actorUserId", actorUserId.toString());
        metadata.put("phase", "ENQUEUE");
        metadata.put("result", "BLOCKED");
        metadata.put("reason", "UPDATE_REQUIRED");
        if (status != null) {
            put(metadata, "minimumVersion", status.minimumVersion());
            put(metadata, "latestVersion", status.latestVersion());
        }
        log(actorUserId, "LOCAL_AGENT_DISPATCH_BLOCKED_UPDATE_REQUIRED", "LOCAL_AGENT_DEVICE", agentId,
                "Local Agent tool dispatch was blocked because an update is required.", metadata);
    }

    private Map<String, Object> deviceMetadata(UUID userId, LocalAgentDevice device) {
        return common(userId, device.agentId(), device.installationId(), device.agentVersion());
    }

    private Map<String, Object> common(UUID userId, UUID agentId, UUID installationId, String agentVersion) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "userId", userId);
        put(metadata, "agentId", agentId);
        put(metadata, "installationId", installationId);
        put(metadata, "agentVersion", agentVersion);
        return metadata;
    }

    private void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) metadata.put(key, value.toString());
    }

    private void log(UUID actorUserId, String action, String targetType, UUID targetId,
                     String message, Map<String, Object> metadata) {
        if (auditService == null) return;
        auditService.logByUserId(actorUserId, action, targetType, targetId.toString(), null, message, metadata);
    }
}
