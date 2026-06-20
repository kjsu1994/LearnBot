package com.learnbot.service;

import java.util.Map;

public record DocumentGraphEdge(
        String sourceKey,
        String targetKey,
        String type,
        double weight,
        Map<String, Object> metadata
) {
}
