package com.learnbot.web;

import com.learnbot.repository.LocalAgentDeviceRepository;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AppUser;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentAuditService;
import com.learnbot.service.LocalAgentCredentialRotationService;
import com.learnbot.service.LocalAgentDevice;
import com.learnbot.service.LocalAgentEnrollmentService;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentToken;
import com.learnbot.service.LocalAgentVersionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentEnrollmentControllerTest {
    private final LocalAgentEnrollmentService enrollmentService = mock(LocalAgentEnrollmentService.class);
    private final LocalAgentCredentialRotationService rotationService = mock(LocalAgentCredentialRotationService.class);
    private final LocalAgentDeviceRepository deviceRepository = mock(LocalAgentDeviceRepository.class);
    private final LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
    private final LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
    private final LocalAgentVersionPolicy versionPolicy = mock(LocalAgentVersionPolicy.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final LocalAgentAuditService auditService = mock(LocalAgentAuditService.class);
    private final LocalAgentEnrollmentController controller = new LocalAgentEnrollmentController(
            enrollmentService, rotationService, deviceRepository, authService, gatewayService,
            versionPolicy, currentUserProvider, auditService
    );

    @Test
    void revokeSelfUsesAgentCredentialAndClearsDeviceLifecycle() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        LocalAgentToken token = new LocalAgentToken(
                UUID.randomUUID(), userId, agentId, "laptop", OffsetDateTime.now().plusDays(1),
                null, null, OffsetDateTime.now()
        );
        LocalAgentDevice device = mock(LocalAgentDevice.class);
        when(authService.authenticate("agent-token")).thenReturn(token);
        when(deviceRepository.findActiveByUserAndAgent(userId, agentId)).thenReturn(Optional.of(device));
        when(deviceRepository.revoke(any(), any(), any())).thenReturn(true);
        when(deviceRepository.findSelectedByUser(userId)).thenReturn(Optional.empty());

        var response = controller.revokeSelf("agent-token");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(authService).revokeAgent(userId, agentId);
        verify(deviceRepository).revoke(any(), any(), any());
        verify(gatewayService).disconnect(userId, agentId);
        verify(gatewayService).clearSelection(userId);
        verify(auditService).deviceSelfRevoked(token, device);
    }

    @Test
    void selectDeviceMakesExplicitDeviceTheUserDefault() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        LocalAgentDevice device = mock(LocalAgentDevice.class);
        when(device.agentId()).thenReturn(agentId);
        when(device.agentVersion()).thenReturn("1.0.0");
        when(device.selectedAt()).thenReturn(OffsetDateTime.now());
        when(currentUserProvider.currentUser()).thenReturn(
                new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE")
        );
        when(deviceRepository.selectForUser(any(), any(), any())).thenReturn(true);
        when(deviceRepository.findActiveByUserAndAgent(userId, agentId)).thenReturn(Optional.of(device));
        when(gatewayService.status(userId, agentId)).thenReturn(com.learnbot.dto.LocalAgentStatusResponse.disconnected());
        when(versionPolicy.evaluate("1.0.0")).thenReturn(
                new LocalAgentVersionPolicy.Decision("1.0.0", "1.0.0", "CURRENT", "/downloads/agent")
        );

        var response = controller.selectDevice(agentId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().selected()).isTrue();
        verify(gatewayService).select(userId, agentId);
        verify(auditService).deviceSelected(userId, device);
    }

    @Test
    void revokeDeviceAuditsTheUserActorAndResolvedDevice() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        LocalAgentDevice device = mock(LocalAgentDevice.class);
        when(currentUserProvider.currentUser()).thenReturn(
                new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE")
        );
        when(deviceRepository.findActiveByUserAndAgent(userId, agentId)).thenReturn(Optional.of(device));
        when(deviceRepository.revoke(any(), any(), any())).thenReturn(true);
        when(deviceRepository.findSelectedByUser(userId)).thenReturn(Optional.empty());

        var response = controller.revokeDevice(agentId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(auditService).deviceRevokedByUser(userId, device);
    }

    @Test
    void enrollmentCreateUsesTrustedRealIpInsteadOfSpoofableForwardedChain() {
        var requestBody = new LocalAgentEnrollmentService.CreateRequest(
                "laptop", "setup", "DESKTOP-01", "Windows", "11", "x64", "1.0.0", UUID.randomUUID()
        );
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-Forwarded-For", "203.0.113.250");
        servletRequest.addHeader("X-Real-IP", "198.51.100.20");

        controller.createEnrollment(requestBody, servletRequest);

        verify(enrollmentService).create(eq(requestBody), eq("198.51.100.20"));
    }

    @Test
    void enrollmentExchangeUsesTrustedRealIpForItsPreLookupRateLimit() {
        var requestBody = new LocalAgentEnrollmentService.ExchangeRequest("device-code");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-Forwarded-For", "203.0.113.250");
        servletRequest.addHeader("X-Real-IP", "198.51.100.20");

        controller.exchangeEnrollment(requestBody, servletRequest);

        verify(enrollmentService).exchange(eq(requestBody), eq("198.51.100.20"));
    }
}
