package com.learnbot.dto;

import java.util.List;

public record AdminSettingsUpdateRequest(
        Boolean respectRobotsTxt,
        List<String> allowedDomains
) {
}
