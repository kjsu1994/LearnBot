package com.learnbot.dto;

import java.util.List;

public record DocumentPreviewTable(
        String name,
        List<List<String>> rows
) {
}
