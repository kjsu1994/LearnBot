package com.learnbot.service;

import java.util.Map;

public record Chunk(
        int index,
        String content,
        Map<String, Object> metadata
) {
}
