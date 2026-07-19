package com.learnbot.service;

import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAgentGatewayServiceTest {
    @Test
    void statusIsDisconnectedWhenNoAgentHeartbeatExists() {
        LocalAgentGatewayService service = new LocalAgentGatewayService();

        var status = service.status(UUID.randomUUID());

        assertThat(status.state()).isEqualTo(LocalAgentConnectionState.DISCONNECTED);
        assertThat(status.agentId()).isNull();
        assertThat(status.message()).contains("No Local Agent");
    }

    @Test
    void heartbeatRegistersConnectedAgentAndApprovedWorkspace() {
        LocalAgentGatewayService service = new LocalAgentGatewayService();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        OffsetDateTime nextRetryAt = OffsetDateTime.now().plusSeconds(15);

        service.registerHeartbeat(
                userId,
                agentId,
                "0.1.0",
                List.of("file.read", "git.status"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "api", "C:/work/api", true)),
                "auto",
                "polling-fallback",
                2,
                nextRetryAt
        );

        var status = service.status(userId);

        assertThat(status.state()).isEqualTo(LocalAgentConnectionState.CONNECTED);
        assertThat(status.agentId()).isEqualTo(agentId);
        assertThat(status.capabilities()).containsExactly("file.read", "git.status");
        assertThat(status.configuredTransport()).isEqualTo("auto");
        assertThat(status.activeTransport()).isEqualTo("polling-fallback");
        assertThat(status.webSocketFailureCount()).isEqualTo(2);
        assertThat(status.nextWebSocketRetryAt()).isEqualTo(nextRetryAt);
        assertThat(service.hasApprovedWorkspace(userId, workspaceId)).isTrue();
    }

    @Test
    void heartbeatPreservesPreviouslyApprovedWorkspaceForSameAgentWhenIncomingListIsStale() {
        LocalAgentGatewayService service = new LocalAgentGatewayService();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        service.registerHeartbeat(
                userId,
                agentId,
                "0.1.0",
                List.of("file.read"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "test", "C:/Users/honeybadger/Desktop/test", true))
        );
        service.registerHeartbeat(
                userId,
                agentId,
                "0.1.0",
                List.of("file.read"),
                List.of()
        );

        var status = service.status(userId);

        assertThat(status.state()).isEqualTo(LocalAgentConnectionState.CONNECTED);
        assertThat(status.workspaces()).extracting(LocalAgentWorkspaceSummary::workspaceId)
                .containsExactly(workspaceId);
        assertThat(service.hasApprovedWorkspace(userId, workspaceId)).isTrue();
    }

    @Test
    void disconnectRemovesOnlyMatchingAgent() {
        LocalAgentGatewayService service = new LocalAgentGatewayService();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        service.registerHeartbeat(userId, agentId, "0.1.0", List.of(), List.of());

        service.disconnect(userId, UUID.randomUUID());
        assertThat(service.status(userId).state()).isEqualTo(LocalAgentConnectionState.CONNECTED);

        service.disconnect(userId, agentId);
        assertThat(service.status(userId).state()).isEqualTo(LocalAgentConnectionState.DISCONNECTED);
    }

    @Test
    void heartbeatsFromMultipleDevicesDoNotOverwriteEachOther() {
        LocalAgentGatewayService service = new LocalAgentGatewayService();
        UUID userId = UUID.randomUUID();
        UUID firstAgent = UUID.randomUUID();
        UUID secondAgent = UUID.randomUUID();
        UUID firstWorkspace = UUID.randomUUID();
        UUID secondWorkspace = UUID.randomUUID();

        service.registerHeartbeat(userId, firstAgent, "0.1.0", List.of("file.read"), List.of(
                new LocalAgentWorkspaceSummary(firstWorkspace, "first", "C:/first", true)
        ));
        service.registerHeartbeat(userId, secondAgent, "0.1.0", List.of("git.status"), List.of(
                new LocalAgentWorkspaceSummary(secondWorkspace, "second", "C:/second", true)
        ));

        assertThat(service.statuses(userId)).extracting(status -> status.agentId())
                .containsExactlyInAnyOrder(firstAgent, secondAgent);
        assertThat(service.status(userId, firstAgent).capabilities()).containsExactly("file.read");
        assertThat(service.status(userId, secondAgent).capabilities()).containsExactly("git.status");
        assertThat(service.status(userId).agentId()).isEqualTo(firstAgent);
        assertThat(service.hasApprovedWorkspace(userId, firstWorkspace)).isTrue();
        assertThat(service.hasApprovedWorkspace(userId, secondWorkspace)).isFalse();

        service.select(userId, secondAgent);

        assertThat(service.status(userId).agentId()).isEqualTo(secondAgent);
        assertThat(service.hasApprovedWorkspace(userId, firstWorkspace)).isFalse();
        assertThat(service.hasApprovedWorkspace(userId, secondWorkspace)).isTrue();

        service.disconnect(userId, secondAgent);

        assertThat(service.status(userId).agentId()).isEqualTo(firstAgent);
    }
}
