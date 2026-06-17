package com.learnbot.dto;

import java.util.List;

public record DocumentPreviewSheet(
        String name,
        List<List<String>> rows
) {
}
