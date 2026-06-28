package com.learnbot.service;

import java.util.UUID;

public interface LocalAgentTokenRevocationListener {
    void onTokenRevoked(UUID userId, UUID tokenId);
}
