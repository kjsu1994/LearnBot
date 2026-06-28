package com.learnbot.service;

import com.learnbot.dto.LocalAgentPairingTokenResponse;
import com.learnbot.dto.LocalAgentTokenSummary;
import com.learnbot.repository.LocalAgentTokenRepository;
import com.learnbot.security.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalAgentAuthService(
            LocalAgentTokenRepository repository,
            List<LocalAgentTokenRevocationListener> revocationListeners
    ) {
        this.repository = repository;
        this.revocationListeners = revocationListeners;
    }

    @Transactional
    public LocalAgentPairingTokenResponse issueToken(UUID userId, String label) {
        UUID tokenId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        String rawToken = newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(TOKEN_DAYS);
        repository.create(tokenId, userId, agentId, cleanLabel(label), tokenHash(rawToken), expiresAt);
        return new LocalAgentPairingTokenResponse(tokenId, agentId, rawToken, expiresAt);
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Token hashing is unavailable.", ex);
        }
    }
}
