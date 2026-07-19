package com.learnbot.service;

import com.learnbot.repository.LocalAgentCredentialRotationRepository;
import com.learnbot.security.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class LocalAgentCredentialRotationService {
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_DAYS = 30;
    private static final int CONFIRM_HOURS = 24;

    private final LocalAgentCredentialRotationRepository repository;
    private final LocalAgentAuthService authService;
    private final LocalAgentAuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalAgentCredentialRotationService(
            LocalAgentCredentialRotationRepository repository,
            LocalAgentAuthService authService
    ) {
        this(repository, authService, LocalAgentAuditService.noop());
    }

    @Autowired
    public LocalAgentCredentialRotationService(
            LocalAgentCredentialRotationRepository repository,
            LocalAgentAuthService authService,
            LocalAgentAuditService auditService
    ) {
        this.repository = repository;
        this.authService = authService;
        this.auditService = auditService;
    }

    @Transactional
    public RotationResponse initiate(String currentRawToken) {
        LocalAgentToken current = authService.authenticate(currentRawToken);
        OffsetDateTime now = OffsetDateTime.now();
        String candidate = newToken();
        LocalAgentCredentialRotation rotation = new LocalAgentCredentialRotation(
                UUID.randomUUID(), current.userId(), current.agentId(), current.id(), UUID.randomUUID(),
                now.plusDays(TOKEN_DAYS), now.plusHours(CONFIRM_HOURS), null, null, now
        );
        repository.cancelPending(current.userId(), current.agentId(), now);
        repository.create(rotation, LocalAgentSecretHasher.sha256(candidate));
        auditService.credentialRotationInitiated(current, rotation);
        return new RotationResponse(
                "learnbot.local-agent.credential-rotation.v1", rotation.id(), candidate,
                rotation.candidateExpiresAt(), rotation.confirmBy()
        );
    }

    @Transactional
    public ConfirmationResponse confirm(UUID rotationId, String candidateRawToken) {
        if (candidateRawToken == null || candidateRawToken.isBlank()) {
            throw new UnauthorizedException("Candidate Local Agent token is required.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentCredentialRotation rotation = repository.findCandidateForUpdate(
                        rotationId, LocalAgentSecretHasher.sha256(candidateRawToken))
                .orElseThrow(() -> new UnauthorizedException("Credential rotation is invalid or expired."));
        if (rotation.confirmedAt() != null) {
            LocalAgentToken activeCandidate = authService.authenticate(candidateRawToken);
            if (!rotation.candidateTokenId().equals(activeCandidate.id())
                    || !rotation.userId().equals(activeCandidate.userId())
                    || !rotation.agentId().equals(activeCandidate.agentId())) {
                throw new UnauthorizedException("Credential rotation is invalid or expired.");
            }
            auditService.credentialRotationConfirmed(rotation, true);
            return confirmation(rotation, rotation.confirmedAt());
        }
        if (!rotation.pendingAt(now)) {
            repository.cancel(rotation.id(), now);
            throw new UnauthorizedException("Credential rotation is invalid or expired.");
        }
        authService.activateCredential(
                rotation.userId(), rotation.agentId(), "rotated", rotation.candidateTokenId(),
                candidateRawToken, rotation.candidateExpiresAt()
        );
        if (!repository.confirm(rotation.id(), now)) {
            throw new IllegalStateException("Credential rotation could not be confirmed atomically.");
        }
        if (!authService.revokeToken(rotation.userId(), rotation.currentTokenId())) {
            throw new UnauthorizedException("The previous Local Agent credential is no longer active.");
        }
        auditService.credentialRotationConfirmed(rotation, false);
        return confirmation(rotation, now);
    }

    private ConfirmationResponse confirmation(LocalAgentCredentialRotation rotation, OffsetDateTime confirmedAt) {
        return new ConfirmationResponse(
                "learnbot.local-agent.credential-rotation-confirmation.v1", rotation.id(),
                rotation.agentId(), "CONFIRMED", confirmedAt, rotation.candidateExpiresAt()
        );
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RotationResponse(String schema, UUID rotationId, String token,
                                   OffsetDateTime expiresAt, OffsetDateTime confirmBy) { }

    public record ConfirmationResponse(String schema, UUID rotationId, UUID agentId, String status,
                                       OffsetDateTime confirmedAt, OffsetDateTime expiresAt) { }
}
