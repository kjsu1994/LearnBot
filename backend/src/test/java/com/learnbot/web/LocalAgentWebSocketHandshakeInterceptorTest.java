package com.learnbot.web;

import com.learnbot.security.UnauthorizedException;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalAgentWebSocketHandshakeInterceptorTest {
    private final LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
    private final LocalAgentWebSocketHandshakeInterceptor interceptor = new LocalAgentWebSocketHandshakeInterceptor(authService);

    @Test
    void rejectsMissingToken() {
        boolean accepted = interceptor.beforeHandshake(
                request(null),
                mock(ServerHttpResponse.class),
                null,
                new HashMap<>()
        );

        assertThat(accepted).isFalse();
    }

    @Test
    void rejectsInvalidToken() {
        when(authService.authenticate("bad")).thenThrow(new UnauthorizedException("invalid"));

        boolean accepted = interceptor.beforeHandshake(
                request("bad"),
                mock(ServerHttpResponse.class),
                null,
                new HashMap<>()
        );

        assertThat(accepted).isFalse();
    }

    @Test
    void acceptsValidTokenAndBindsAgentIdentity() {
        UUID tokenId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(authService.authenticate("good")).thenReturn(new LocalAgentToken(
                tokenId,
                userId,
                agentId,
                "laptop",
                OffsetDateTime.now().plusDays(1),
                null,
                null,
                OffsetDateTime.now()
        ));
        HashMap<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request("good"),
                mock(ServerHttpResponse.class),
                null,
                attributes
        );

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(LocalAgentWebSocketAttributes.USER_ID, userId);
        assertThat(attributes).containsEntry(LocalAgentWebSocketAttributes.AGENT_ID, agentId);
        assertThat(attributes).containsEntry(LocalAgentWebSocketAttributes.TOKEN_ID, tokenId);
    }

    private ServerHttpRequest request(String token) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.add("X-Local-Agent-Token", token);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }
}
