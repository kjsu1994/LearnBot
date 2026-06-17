package com.learnbot.dto;

import java.util.List;

public record DocumentPreviewBlock(
        String type,
        Integer level,
        String text,
        List<String> items,
        List<List<String>> rows,
        String href
) {
}
