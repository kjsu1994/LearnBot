package com.learnbot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record WebIngestRequest(
        @NotBlank String url,
        UUID spaceId,
        Boolean recursive,
        Integer maxDepth,
        Integer maxPages,
        String crawlScope,
        String robotsFailurePolicy,
        Boolean includeAttachments,
        Boolean useSitemap,
        String renderMode
) {
}
