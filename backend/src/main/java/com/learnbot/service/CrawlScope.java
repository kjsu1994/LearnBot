package com.learnbot.service;

public enum CrawlScope {
    START_PATH,
    SAME_HOST,
    SAME_SITE,
    ALLOWLIST;

    public static CrawlScope from(String value) {
        if (value == null || value.isBlank()) {
            return START_PATH;
        }
        try {
            return CrawlScope.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return START_PATH;
        }
    }
}
