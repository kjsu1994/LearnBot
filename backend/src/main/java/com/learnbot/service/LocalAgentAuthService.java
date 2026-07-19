package com.learnbot.service;

import com.learnbot.dto.LocalAgentPairingTokenResponse;
import com.learnbot.dto.LocalAgentTokenSummary;
import com.learnbot.repository.LocalAgentDeviceRepository;
import com.learnbot.repository.LocalAgentTokenRepository;
import com.learnbot.security.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class LocalAgentAuthService {
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_DAYS = 30;

    private final LocalAgentTokenRepository repository;
    private final List<LocalAgentTokenRevocationListener> revocationListeners;
    private final LocalAgentDeviceRepository deviceRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalAgentAuthService(
            LocalAgentTokenRepository repository,
            List<LocalAgentTokenRevocationListener> revocationListeners
    ) {
        this(repository, revocationListeners, null);
    }

    @Autowired
    public LocalAgentAuthService(
            LocalAgentTokenRepository repository,
            List<LocalAgentTokenRevocationListener> revocationListeners,
            LocalAgentDeviceRepository deviceRepository
    ) {
        this.repository = repository;
        this.revocationListeners = revocationListeners;
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public LocalAgentPairingTokenResponse issueToken(UUID userId, String label) {
        return issueTokenForAgent(userId, UUID.randomUUID(), label);
    }

    @Transactional
    public LocalAgentPairingTokenResponse issueTokenForAgent(UUID userId, UUID agentId, String label) {
        LocalAgentPairingTokenResponse prepared = prepareCredential(agentId);
        activateCredential(userId, agentId, label, prepared.tokenId(), prepared.token(), prepared.expiresAt());
        return prepared;
    }

    public LocalAgentPairingTokenResponse prepareCredential(UUID agentId) {
        return new LocalAgentPairingTokenResponse(
                UUID.randomUUID(), agentId, newToken(), OffsetDateTime.now().plusDays(TOKEN_DAYS)
        );
    }

    @Transactional
    public void activateCredential(
            UUID userId,
            UUID agentId,
            String label,
            UUID tokenId,
            String rawToken,
            OffsetDateTime expiresAt
    ) {
        if (deviceRepository != null) {
            deviceRepository.ensureDevice(userId, agentId, cleanLabel(label), OffsetDateTime.now());
        }
        repository.create(tokenId, userId, agentId, cleanLabel(label), tokenHash(rawToken), expiresAt);
    }

    @Transactional(readOnly = true)
    public List<LocalAgentTokenSummary> listTokens(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        return repository.listByUser(userId).stream()
                .map(token -> new LocalAgentTokenSummary(
                        token.id(),
                        token.agentId(),
                        token.label(),
                        token.expiresAt(),
                        token.revokedAt(),
                        token.lastSeenAt(),
                        token.createdAt(),
                        token.activeAt(now)
                ))
                .toList();
    }

    @Transactional
    public boolean revokeToken(UUID userId, UUID tokenId) {
        boolean revoked = repository.revokeForUser(userId, tokenId);
        if (revoked) {
            revocationListeners.forEach(listener -> listener.onTokenRevoked(userId, tokenId));
        }
        return revoked;
    }

    @Transactional
    public List<UUID> revokeAgent(UUID userId, UUID agentId) {
        List<UUID> revoked = repository.revokeAllForAgent(userId, agentId);
        revoked.forEach(tokenId -> revocationListeners.forEach(listener -> listener.onTokenRevoked(userId, tokenId)));
        return revoked;
    }

    @Transactional
    public LocalAgentToken authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("Local Agent token is required.");
        }
        LocalAgentToken token = repository.findByTokenHash(tokenHash(rawToken))
                .orElseThrow(() -> new UnauthorizedException("Local Agent token is invalid or expired."));
        if (!token.activeAt(OffsetDateTime.now())) {
            throw new UnauthorizedException("Local Agent token is invalid or expired.");
        }
        repository.markSeen(token.id());
        return token;
    }

    private String cleanLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        return LocalAgentSecretHasher.sha256(token);
    }
}
