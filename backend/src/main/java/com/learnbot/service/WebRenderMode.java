package com.learnbot.service;

public enum WebRenderMode {
    STATIC,
    PLAYWRIGHT_FALLBACK,
    PLAYWRIGHT_ALWAYS;

    public static WebRenderMode from(String value) {
        if (value == null || value.isBlank()) {
            return STATIC;
        }
        try {
            return WebRenderMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return STATIC;
        }
    }
}
