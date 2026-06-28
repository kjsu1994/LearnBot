package com.learnbot.web;

import com.learnbot.dto.LocalAgentPatchReleaseAttemptModel;
import com.learnbot.dto.LocalAgentPatchReleaseBoundaryResponse;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AppUser;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentToolGatewayService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentControllerTest {
    @Test
    void releasePatchExecutionReturnsRefusalOnlyBoundaryWithoutClaiming() {
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentAuthService authService = mock(LocalAgentAuthService.class);
        LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        LocalAgentController controller = new LocalAgentController(
                gatewayService,
                authService,
                toolGatewayService,
                currentUserProvider
        );
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AppUser user = new AppUser(userId, "user@example.com", "User", "USER", "ACTIVE");
        LocalAgentPatchReleaseBoundaryResponse expected = new LocalAgentPatchReleaseBoundaryResponse(
                requestId,
                "RELEASE_REFUSED_GATE_DISABLED",
                "REFUSAL_ONLY",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of("release gate is disabled"),
                "Release action is modeled, but disabled.",
                Map.of("releaseGateEnabled", false),
                Map.of("releaseGateEnabled", false),
                new LocalAgentPatchReleaseAttemptModel(
                        "learnbot.local-agent.patch-release-attempt.v1",
                        "DISABLED",
                        true,
                        false,
                        120,
                        List.of(),
                        Map.of(),
                        "disabled"
                )
        );
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(toolGatewayService.inspectPatchReleaseBoundary(userId, requestId)).thenReturn(expected);

        var actual = controller.releasePatchExecution(requestId);

        assertThat(actual).isSameAs(expected);
        verify(toolGatewayService).inspectPatchReleaseBoundary(userId, requestId);
    }
}
