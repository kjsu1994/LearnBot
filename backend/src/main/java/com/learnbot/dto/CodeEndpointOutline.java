package com.learnbot.dto;

import java.util.UUID;

/** A directly indexed endpoint structure attached to one active source chunk. */
public record CodeEndpointOutline(
        UUID chunkId,
        String route,
        String httpMethod
) {
}
