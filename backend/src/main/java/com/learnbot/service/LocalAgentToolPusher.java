package com.learnbot.service;

import com.learnbot.dto.LocalAgentQueuedToolRequest;

public interface LocalAgentToolPusher {
    boolean sendToolRequest(LocalAgentQueuedToolRequest queued);
}
