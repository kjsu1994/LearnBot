package com.learnbot.dto;

import java.util.List;
import java.util.UUID;

public record DocumentPreviewResponse(
        UUID documentId,
        String title,
        String sourceUri,
        String sourceType,
        String contentType,
        String previewType,
        String filename,
        Long sizeBytes,
        boolean originalAvailable,
        boolean truncated,
        String text,
        List<String> paragraphs,
        List<DocumentPreviewTable> tables,
        List<DocumentPreviewSheet> sheets,
        List<DocumentPreviewBlock> blocks,
        boolean renderedAvailable,
        String renderedContentType,
        String previewFallbackReason
) {
}
