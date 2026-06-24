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
        String documentType,
        String clauseNumber,
        String clauseLevel
) {
    public DocumentConversationAnchor(
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
        this(chunkId, documentId, title, sourceUri, chunkIndex, pageNumber, sectionTitle, headingPath, documentType, "", "");
    }
}
