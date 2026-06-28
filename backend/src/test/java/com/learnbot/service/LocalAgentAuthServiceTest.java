package com.learnbot.service;

import com.learnbot.repository.LocalAgentTokenRepository;
import com.learnbot.security.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentAuthServiceTest {
    private final LocalAgentTokenRepository repository = mock(LocalAgentTokenRepository.class);
    private final LocalAgentTokenRevocationListener revocationListener = mock(LocalAgentTokenRevocationListener.class);
    private final LocalAgentAuthService service = new LocalAgentAuthService(repository, List.of(revocationListener));

    @Test
    void issueTokenReturnsRawTokenOnceAndStoresOnlyHash() {
        UUID userId = UUID.randomUUID();

        var response = service.issueToken(userId, " laptop ");

        assertThat(response.token()).isNotBlank();
        assertThat(response.agentId()).isNotNull();
        verify(repository).create(eq(response.tokenId()), eq(userId), eq(response.agentId()), eq("laptop"), anyString(), eq(response.expiresAt()));
    }

    @Test
    void authenticateMarksValidTokenSeen() {
        UUID tokenId = UUID.randomUUID();
        LocalAgentToken token = new LocalAgentToken(
                tokenId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "laptop",
                OffsetDateTime.now().plusDays(1),
                null,
                null,
                OffsetDateTime.now()
        );
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        LocalAgentToken authenticated = service.authenticate("agent-token");

        assertThat(authenticated).isEqualTo(token);
        verify(repository).markSeen(tokenId);
    }

    @Test
    void authenticateRejectsExpiredOrRevokedToken() {
        LocalAgentToken token = new LocalAgentToken(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "laptop",
                OffsetDateTime.now().minusSeconds(1),
                null,
                null,
                OffsetDateTime.now()
        );
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.authenticate("agent-token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void listTokensReturnsSummariesWithoutRawSecret() {
        UUID userId = UUID.randomUUID();
        LocalAgentToken active = new LocalAgentToken(
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                "laptop",
                OffsetDateTime.now().plusDays(1),
                null,
                OffsetDateTime.now().minusMinutes(3),
                OffsetDateTime.now().minusHours(1)
        );
        LocalAgentToken revoked = new LocalAgentToken(
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                "old",
                OffsetDateTime.now().plusDays(1),
                OffsetDateTime.now().minusMinutes(1),
                null,
                OffsetDateTime.now().minusDays(1)
        );
        when(repository.listByUser(userId)).thenReturn(List.of(active, revoked));

        var summaries = service.listTokens(userId);

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).id()).isEqualTo(active.id());
        assertThat(summaries.get(0).active()).isTrue();
        assertThat(summaries.get(1).id()).isEqualTo(revoked.id());
        assertThat(summaries.get(1).active()).isFalse();
    }

    @Test
    void revokeTokenIsScopedToCurrentUser() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(repository.revokeForUser(userId, tokenId)).thenReturn(true);

        assertThat(service.revokeToken(userId, tokenId)).isTrue();
        verify(revocationListener).onTokenRevoked(userId, tokenId);
    }

    @Test
    void revokeTokenDoesNotNotifyListenersWhenTokenIsNotFound() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        when(repository.revokeForUser(userId, tokenId)).thenReturn(false);

        assertThat(service.revokeToken(userId, tokenId)).isFalse();
        verify(revocationListener, never()).onTokenRevoked(userId, tokenId);
    }
}
