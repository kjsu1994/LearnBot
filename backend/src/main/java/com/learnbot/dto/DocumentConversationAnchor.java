package com.learnbot.dto;

import java.util.UUID;

public record DocumentConversationAnchor(
        UUID chunkId,
        UUID documentId,
        String title,
        String sourceUri,
        int chunkIndex,
        Integer pageNumber,
        String sectionTitle,
        String headingPath,
        String documentType
) {
}
