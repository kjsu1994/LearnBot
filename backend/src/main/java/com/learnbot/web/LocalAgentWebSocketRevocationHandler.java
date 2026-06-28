package com.learnbot.web;

import com.learnbot.service.LocalAgentTokenRevocationListener;
import com.learnbot.service.LocalAgentGatewayService;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LocalAgentWebSocketRevocationHandler implements LocalAgentTokenRevocationListener {
    private final LocalAgentWebSocketConnectionRegistry connectionRegistry;
    private final LocalAgentGatewayService gatewayService;

    public LocalAgentWebSocketRevocationHandler(
            LocalAgentWebSocketConnectionRegistry connectionRegistry,
            LocalAgentGatewayService gatewayService
    ) {
        this.connectionRegistry = connectionRegistry;
        this.gatewayService = gatewayService;
    }

    @Override
    public void onTokenRevoked(UUID userId, UUID tokenId) {
        UUID agentId = connectionRegistry.closeTokenSession(userId, tokenId);
        if (agentId != null) {
            gatewayService.disconnect(userId, agentId);
        }
    }
}
