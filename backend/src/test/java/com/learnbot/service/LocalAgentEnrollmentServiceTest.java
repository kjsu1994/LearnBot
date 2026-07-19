package com.learnbot.service;

import com.learnbot.dto.LocalAgentPairingTokenResponse;
import com.learnbot.repository.LocalAgentCredentialRotationRepository;
import com.learnbot.repository.LocalAgentDeviceRepository;
import com.learnbot.repository.LocalAgentEnrollmentRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentEnrollmentServiceTest {
    private final LocalAgentEnrollmentRepository repository = mock(LocalAgentEnrollmentRepository.class);
    private final LocalAgentDeviceRepository deviceRepository = mock(LocalAgentDeviceRepository.class);
    private final LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
    private final LocalAgentAuditService auditService = mock(LocalAgentAuditService.class);
    private final LocalAgentEnrollmentService service = new LocalAgentEnrollmentService(
            repository, deviceRepository, authService, auditService
    );

    @Test
    void approvalAndDenialDecisionsAreAuditedWithoutEnrollmentSecrets() {
        UUID userId = UUID.randomUUID();
        UUID approvedId = UUID.randomUUID();
        UUID deniedId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(5);
        LocalAgentEnrollment approved = enrollment(
                approvedId, UUID.randomUUID(), null, LocalAgentEnrollmentState.PENDING, null, expiresAt
        );
        LocalAgentEnrollment denied = enrollment(
                deniedId, UUID.randomUUID(), null, LocalAgentEnrollmentState.PENDING, null, expiresAt
        );
        when(repository.findByIdForUpdate(approvedId)).thenReturn(Optional.of(approved));
        when(repository.findByIdForUpdate(deniedId)).thenReturn(Optional.of(denied));
        when(repository.approve(any(), any(), any())).thenReturn(true);
        when(repository.deny(any(), any(), any())).thenReturn(true);

        service.decide(userId, approvedId,
                new LocalAgentEnrollmentService.DecisionRequest(LocalAgentEnrollmentService.Decision.APPROVE));
        service.decide(userId, deniedId,
                new LocalAgentEnrollmentService.DecisionRequest(LocalAgentEnrollmentService.Decision.DENY));

        verify(auditService).enrollmentDecision(
                userId, approved.id(), approved.agentId(), approved.installationId(), approved.agentVersion(), "APPROVE"
        );
        verify(auditService).enrollmentDecision(
                userId, denied.id(), denied.agentId(), denied.installationId(), denied.agentVersion(), "DENY"
        );
    }

    @Test
    void createPersistsOnlyHashedCodesAndCleansOldRateLimits() {
        when(repository.consumeRateLimit(anyString(), anyString(), any(), any()))
                .thenReturn(new LocalAgentEnrollmentRepository.RateLimitResult(OffsetDateTime.now(), 1));
        var request = new LocalAgentEnrollmentService.CreateRequest(
                "laptop", "LearnBot Setup", "DESKTOP-01", "Windows", "11", "x64", "1.0.0",
                UUID.randomUUID()
        );

        var response = service.create(request, "127.0.0.1");

        assertThat(response.status()).isEqualTo("PENDING_BROWSER_APPROVAL");
        assertThat(response.deviceCode()).hasSizeGreaterThan(32);
        assertThat(response.userCode()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(response.verificationUriPath()).isEqualTo("/settings/local-agent/connect");
        assertThat(response.verificationUriCompletePath()).startsWith("/settings/local-agent/connect?user_code=");
        verify(repository, atLeastOnce()).cleanupRateLimits(any(OffsetDateTime.class));
        verify(repository).create(any(LocalAgentEnrollment.class), anyString(), anyString());
    }

    @Test
    void approvedExchangeStagesInactiveCandidateForConfirmation() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID enrollmentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentEnrollment enrollment = enrollment(
                enrollmentId, agentId, userId, LocalAgentEnrollmentState.APPROVED, null, now.plusMinutes(5)
        );
        LocalAgentPairingTokenResponse credential = new LocalAgentPairingTokenResponse(
                UUID.randomUUID(), agentId, "new-agent-token", now.plusDays(30)
        );
        when(repository.findByDeviceCodeHashForUpdate(anyString())).thenReturn(Optional.of(enrollment));
        when(repository.consumeRateLimit(anyString(), anyString(), any(), any()))
                .thenReturn(new LocalAgentEnrollmentRepository.RateLimitResult(now, 1));
        when(authService.prepareCredential(agentId)).thenReturn(credential);
        when(repository.stageCandidate(any(), any(), anyString(), any(), any(), any())).thenReturn(true);

        var response = service.exchange(
                new LocalAgentEnrollmentService.ExchangeRequest("device-code"), "198.51.100.20"
        );

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.token()).isEqualTo("new-agent-token");
        assertThat(response.enrollmentId()).isEqualTo(enrollmentId);
        assertThat(response.confirmationRequired()).isTrue();
        verify(repository).stageCandidate(any(), any(), anyString(), any(), any(), any());
        verify(deviceRepository, never()).upsertEnrollment(any(), any());
        verify(repository, never()).consume(any(), any(), any());
    }

    @Test
    void confirmationRevokesPreviousDeviceForSameInstallationBeforeActivatingCredential() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        UUID candidateId = UUID.randomUUID();
        LocalAgentEnrollment enrollment = withCandidate(enrollment(
                UUID.randomUUID(), agentId, userId, LocalAgentEnrollmentState.APPROVED, null, now.plusMinutes(5)
        ), candidateId, now.plusDays(30), now.plusMinutes(5), null);
        LocalAgentDevice previous = mock(LocalAgentDevice.class);
        UUID previousAgentId = UUID.randomUUID();
        when(previous.agentId()).thenReturn(previousAgentId);
        when(repository.findCandidateForUpdate(any(), anyString())).thenReturn(Optional.of(enrollment));
        when(deviceRepository.findOtherActiveByInstallation(userId, enrollment.installationId(), agentId))
                .thenReturn(List.of(previous));
        when(repository.consume(any(), any(), any())).thenReturn(true);

        var response = service.confirm(enrollment.id(), "candidate-token");

        assertThat(response.status()).isEqualTo("CONFIRMED");
        verify(authService).revokeAgent(userId, previousAgentId);
        verify(deviceRepository).revoke(any(), any(), any());
        verify(authService).activateCredential(
                userId, agentId, "laptop", candidateId, "candidate-token", enrollment.candidateExpiresAt()
        );
        verify(repository).consume(any(), any(), any());
    }

    @Test
    void repeatedEnrollmentConfirmationWithActiveCandidateIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentEnrollment enrollment = withCandidate(enrollment(
                UUID.randomUUID(), agentId, userId, LocalAgentEnrollmentState.CONSUMED,
                null, now.plusMinutes(5)
        ), candidateId, now.plusDays(30), now.minusMinutes(1), now.minusMinutes(2));
        LocalAgentToken active = new LocalAgentToken(
                candidateId, userId, agentId, "laptop", enrollment.candidateExpiresAt(),
                null, null, now.minusMinutes(2)
        );
        when(repository.findCandidateForUpdate(any(), anyString())).thenReturn(Optional.of(enrollment));
        when(authService.authenticate("candidate-token")).thenReturn(active);

        var response = service.confirm(enrollment.id(), "candidate-token");

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.confirmedAt()).isEqualTo(enrollment.consumedAt());
        verify(authService, never()).activateCredential(any(), any(), anyString(), any(), anyString(), any());
        verify(repository, never()).consume(any(), any(), any());
    }

    @Test
    void pollingFasterThanPersistedIntervalReturnsSlowDown() {
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentEnrollment enrollment = enrollment(
                UUID.randomUUID(), UUID.randomUUID(), null, LocalAgentEnrollmentState.PENDING,
                now, now.plusMinutes(5)
        );
        when(repository.findByDeviceCodeHashForUpdate(anyString())).thenReturn(Optional.of(enrollment));
        when(repository.consumeRateLimit(anyString(), anyString(), any(), any()))
                .thenReturn(new LocalAgentEnrollmentRepository.RateLimitResult(now, 1));
        when(repository.recordSlowPoll(any(), any())).thenReturn(10);

        var response = service.exchange(
                new LocalAgentEnrollmentService.ExchangeRequest("device-code"), "198.51.100.20"
        );

        assertThat(response.status()).isEqualTo("SLOW_DOWN");
        assertThat(response.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void exchangeRateLimitRejectsFloodBeforeLookingUpTheDeviceCode() {
        OffsetDateTime now = OffsetDateTime.now();
        when(repository.consumeRateLimit(anyString(), anyString(), any(), any()))
                .thenReturn(new LocalAgentEnrollmentRepository.RateLimitResult(now, 301));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.exchange(
                        new LocalAgentEnrollmentService.ExchangeRequest("arbitrary-device-code"), "198.51.100.20"
                ))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("429");

        verify(repository, never()).findByDeviceCodeHashForUpdate(anyString());
    }

    private LocalAgentEnrollment enrollment(
            UUID id,
            UUID agentId,
            UUID userId,
            LocalAgentEnrollmentState state,
            OffsetDateTime lastPolledAt,
            OffsetDateTime expiresAt
    ) {
        return new LocalAgentEnrollment(
                id, agentId, userId, state, "laptop", "LearnBot Setup", "DESKTOP-01",
                "Windows", "11", "x64", "1.0.0", UUID.randomUUID(), 5, 0,
                lastPolledAt, expiresAt, state == LocalAgentEnrollmentState.APPROVED ? OffsetDateTime.now() : null,
                null, state == LocalAgentEnrollmentState.CONSUMED ? OffsetDateTime.now().minusMinutes(1) : null,
                null, null, null, null, OffsetDateTime.now().minusMinutes(1)
        );
    }

    private LocalAgentEnrollment withCandidate(
            LocalAgentEnrollment value,
            UUID candidateTokenId,
            OffsetDateTime candidateExpiresAt,
            OffsetDateTime confirmBy,
            OffsetDateTime consumedAt
    ) {
        return new LocalAgentEnrollment(
                value.id(), value.agentId(), value.userId(), value.state(), value.label(), value.clientName(),
                value.machineName(), value.osName(), value.osVersion(), value.architecture(), value.agentVersion(),
                value.installationId(), value.pollIntervalSeconds(), value.pollViolationCount(), value.lastPolledAt(),
                value.expiresAt(), value.approvedAt(), value.deniedAt(), consumedAt, candidateTokenId,
                candidateExpiresAt, confirmBy, OffsetDateTime.now().minusMinutes(2), value.createdAt()
        );
    }
}

class LocalAgentCredentialRotationServiceTest {
    private final LocalAgentCredentialRotationRepository repository = mock(LocalAgentCredentialRotationRepository.class);
    private final LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
    private final LocalAgentAuditService auditService = mock(LocalAgentAuditService.class);
    private final LocalAgentCredentialRotationService service = new LocalAgentCredentialRotationService(
            repository, authService, auditService
    );

    @Test
    void initiateReturnsCandidateWithoutActivatingIt() {
        LocalAgentToken current = activeToken();
        when(authService.authenticate("current-token")).thenReturn(current);

        var response = service.initiate("current-token");

        assertThat(response.token()).isNotBlank();
        assertThat(response.confirmBy()).isBefore(response.expiresAt());
        verify(repository).cancelPending(any(), any(), any());
        verify(repository).create(any(LocalAgentCredentialRotation.class), anyString());
        verify(auditService).credentialRotationInitiated(any(LocalAgentToken.class), any(LocalAgentCredentialRotation.class));
    }

    @Test
    void confirmActivatesCandidateThenRevokesPreviousCredential() {
        LocalAgentToken current = activeToken();
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentCredentialRotation rotation = new LocalAgentCredentialRotation(
                UUID.randomUUID(), current.userId(), current.agentId(), current.id(), UUID.randomUUID(),
                now.plusDays(30), now.plusHours(24), null, null, now
        );
        when(repository.findCandidateForUpdate(any(), anyString())).thenReturn(Optional.of(rotation));
        when(repository.confirm(any(), any())).thenReturn(true);
        when(authService.revokeToken(current.userId(), current.id())).thenReturn(true);

        var response = service.confirm(rotation.id(), "candidate-token");

        assertThat(response.status()).isEqualTo("CONFIRMED");
        verify(authService).activateCredential(
                current.userId(), current.agentId(), "rotated", rotation.candidateTokenId(),
                "candidate-token", rotation.candidateExpiresAt()
        );
        verify(authService).revokeToken(current.userId(), current.id());
        verify(auditService).credentialRotationConfirmed(rotation, false);
    }

    @Test
    void repeatedConfirmWithAlreadyActiveCandidateIsIdempotent() {
        LocalAgentToken current = activeToken();
        OffsetDateTime now = OffsetDateTime.now();
        UUID candidateId = UUID.randomUUID();
        LocalAgentCredentialRotation rotation = new LocalAgentCredentialRotation(
                UUID.randomUUID(), current.userId(), current.agentId(), current.id(), candidateId,
                now.plusDays(30), now.minusMinutes(1), now.minusMinutes(2), null, now.minusDays(1)
        );
        LocalAgentToken candidate = new LocalAgentToken(
                candidateId, current.userId(), current.agentId(), "rotated", rotation.candidateExpiresAt(),
                null, null, now.minusMinutes(2)
        );
        when(repository.findCandidateForUpdate(any(), anyString())).thenReturn(Optional.of(rotation));
        when(authService.authenticate("candidate-token")).thenReturn(candidate);

        var response = service.confirm(rotation.id(), "candidate-token");

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.confirmedAt()).isEqualTo(rotation.confirmedAt());
        verify(authService, never()).activateCredential(any(), any(), anyString(), any(), anyString(), any());
        verify(authService, never()).revokeToken(any(), any());
        verify(auditService).credentialRotationConfirmed(rotation, true);
    }

    private LocalAgentToken activeToken() {
        return new LocalAgentToken(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "laptop",
                OffsetDateTime.now().plusDays(10), null, null, OffsetDateTime.now().minusDays(1)
        );
    }
}
