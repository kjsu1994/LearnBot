package com.learnbot.dto;

import java.time.Instant;
import java.util.List;

public record AdminTuningRecommendationResponse(
        Instant generatedAt,
        String confidence,
        String summary,
        List<AdminTuningRecommendationChange> changes,
        List<String> notes
) {
}
