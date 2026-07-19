package com.learnbot.service;

import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentStatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentAuditServiceTest {
    private static final Set<String> ALLOWED_METADATA_KEYS = Set.of(
            "userId", "agentId", "installationId", "agentVersion", "actorType", "actorUserId",
            "actorAgentId", "enrollmentId", "decision", "result", "phase", "reason",
            "minimumVersion", "latestVersion"
    );

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void writesSecurityEventsUsingOnlyTheLocalAgentMetadataAllowList() {
        AuditService auditService = mock(AuditService.class);
        LocalAgentAuditService service = new LocalAgentAuditService(auditService);
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        UUID enrollmentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentDevice device = mock(LocalAgentDevice.class);
        when(device.userId()).thenReturn(userId);
        when(device.agentId()).thenReturn(agentId);
        when(device.installationId()).thenReturn(installationId);
        when(device.agentVersion()).thenReturn("1.0.0");
        LocalAgentToken token = new LocalAgentToken(
                UUID.randomUUID(), userId, agentId, "device", now.plusDays(30), null, null, now
        );
        LocalAgentCredentialRotation rotation = new LocalAgentCredentialRotation(
                UUID.randomUUID(), userId, agentId, token.id(), UUID.randomUUID(),
                now.plusDays(30), now.plusHours(24), null, null, now
        );
        LocalAgentStatusResponse status = new LocalAgentStatusResponse(
                LocalAgentConnectionState.CONNECTED, agentId, "0.9.0", now, now, List.of(), List.of(),
                "auto", "polling", 0, null, "Update required.",
                "1.2.0", "1.0.0", "UPDATE_REQUIRED", "/downloads/local-agent"
        );

        service.enrollmentDecision(userId, enrollmentId, agentId, installationId, "1.0.0", "APPROVE");
        service.enrollmentDecision(userId, enrollmentId, agentId, installationId, "1.0.0", "DENY");
        service.deviceSelected(userId, device);
        service.deviceRevokedByUser(userId, device);
        service.deviceSelfRevoked(token, device);
        service.credentialRotationInitiated(token, rotation);
        service.credentialRotationConfirmed(rotation, false);
        service.credentialRotationConfirmed(rotation, true);
        service.updateRequiredDispatchBlocked(userId, agentId, status);

        ArgumentCaptor<String> actions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService, times(9)).logByUserId(
                nullable(UUID.class), actions.capture(), anyString(), anyString(), isNull(),
                anyString(), metadata.capture()
        );
        assertThat(actions.getAllValues()).contains(
                "LOCAL_AGENT_ENROLLMENT_APPROVED",
                "LOCAL_AGENT_ENROLLMENT_DENIED",
                "LOCAL_AGENT_DEVICE_SELECTED",
                "LOCAL_AGENT_DEVICE_REVOKED",
                "LOCAL_AGENT_DEVICE_SELF_REVOKED",
                "LOCAL_AGENT_CREDENTIAL_ROTATION_INITIATED",
                "LOCAL_AGENT_CREDENTIAL_ROTATION_CONFIRMED",
                "LOCAL_AGENT_DISPATCH_BLOCKED_UPDATE_REQUIRED"
        );
        assertThat(metadata.getAllValues()).allSatisfy(values -> {
            assertThat(values.keySet()).allMatch(key -> ALLOWED_METADATA_KEYS.contains(String.valueOf(key)));
            assertThat(values.keySet()).noneMatch(key -> {
                String normalized = String.valueOf(key).toLowerCase();
                return normalized.contains("token") || normalized.contains("code")
                        || normalized.contains("workspace") || normalized.contains("path");
            });
        });
    }

    @Test
    void updateRequiredBlockUsesAnIndependentTransactionSoTheThrowingDispatchCanRollBack() throws Exception {
        Transactional transactional = LocalAgentAuditService.class.getMethod(
                "updateRequiredDispatchBlocked", UUID.class, UUID.class, LocalAgentStatusResponse.class
        ).getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
