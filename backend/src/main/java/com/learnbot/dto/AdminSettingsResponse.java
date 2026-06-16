package com.learnbot.dto;

import java.util.List;

public record AdminSettingsResponse(
        boolean respectRobotsTxt,
        List<String> allowedDomains
) {
}
