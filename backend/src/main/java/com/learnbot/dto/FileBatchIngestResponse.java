package com.learnbot.dto;

import java.util.List;

public record FileBatchIngestResponse(
        int total,
        int succeeded,
        int failed,
        List<FileIngestItemResponse> items
) {
}
