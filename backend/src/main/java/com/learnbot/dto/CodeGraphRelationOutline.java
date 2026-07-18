package com.learnbot.dto;

import java.util.UUID;

/**
 * A bounded, navigation-only view of one active code-graph edge adjacent to an
 * already observed chunk. It exposes an executable operand without loading the
 * neighboring implementation body.
 */
public record CodeGraphRelationOutline(
        UUID edgeId,
        UUID seedChunkId,
        String direction,
        String relationType,
        String seedName,
        String seedQualifiedName,
        String seedPath,
        String neighborName,
        String neighborQualifiedName,
        String neighborPath,
        UUID neighborChunkId,
        double confidence
) {
}
