package com.learnbot.security;

import com.learnbot.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthInterceptorTest {
    @Test
    void cliLoginBypassesAuthenticationSoCliCanEstablishStoredSession() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/cli-login");

        var allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verify(authService, never()).authenticateToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cliDeviceSessionPlanBypassesAuthenticationAsReadOnlyPlan() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/cli-device-session/plan");

        var allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verify(authService, never()).authenticateToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cliDeviceSessionCreatePlanBypassesAuthenticationAsReadOnlyPlan() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/cli-device-session/create/plan");

        var allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verify(authService, never()).authenticateToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cliDeviceSessionClaimPlanBypassesAuthenticationAsReadOnlyPlan() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/cli-device-session/claim/plan");

        var allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verify(authService, never()).authenticateToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cliDeviceSessionClaimResultPlanBypassesAuthenticationAsReadOnlyPlan() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/cli-device-session/claim-result/plan");

        var allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verify(authService, never()).authenticateToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void otherEndpointsStillRequireBearerOrCookieToken() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setCookies(new Cookie("other", "token"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void localAgentSelfRevokeAcceptsAgentTokenForControllerAuthentication() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/local-agents/self");
        request.addHeader("X-Local-Agent-Token", "agent-token");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verify(authService, never()).authenticateToken(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void enrollmentConfirmationAcceptsPendingCandidateToken() {
        AuthService authService = mock(AuthService.class);
        AuthInterceptor interceptor = new AuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/local-agents/enrollments/" + java.util.UUID.randomUUID() + "/confirm"
        );
        request.addHeader("X-Local-Agent-Token", "candidate-token");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verify(authService, never()).authenticateToken(org.mockito.ArgumentMatchers.anyString());
    }
}
