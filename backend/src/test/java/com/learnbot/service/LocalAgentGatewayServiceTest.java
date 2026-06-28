package com.learnbot.service;

import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import org.junit.jupiter.api.Test;

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

        service.registerHeartbeat(
                userId,
                agentId,
                "0.1.0",
                List.of("file.read", "git.status"),
                List.of(new LocalAgentWorkspaceSummary(workspaceId, "api", "C:/work/api", true))
        );

        var status = service.status(userId);

        assertThat(status.state()).isEqualTo(LocalAgentConnectionState.CONNECTED);
        assertThat(status.agentId()).isEqualTo(agentId);
        assertThat(status.capabilities()).containsExactly("file.read", "git.status");
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
}
