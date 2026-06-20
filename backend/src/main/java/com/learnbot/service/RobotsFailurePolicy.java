package com.learnbot.service;

public enum RobotsFailurePolicy {
    FAIL_CLOSED,
    ALLOW_ON_ERROR,
    IGNORE;

    public static RobotsFailurePolicy from(String value) {
        if (value == null || value.isBlank()) {
            return FAIL_CLOSED;
        }
        try {
            return RobotsFailurePolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FAIL_CLOSED;
        }
    }
}
