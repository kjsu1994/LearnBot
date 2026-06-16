package com.learnbot.dto;

import java.util.List;

public record DocumentDetail(
        DocumentSummary summary,
        int chunkCount,
        StoredObjectSummary storedObject,
        List<DocumentChunkDetail> chunks,
        List<CrawlAuditSummary> crawlAudits
) {
}
