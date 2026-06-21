package com.learnbot.service;

import java.util.Map;

public final class DocumentPageMetadata {
    private DocumentPageMetadata() {
    }

    public static Integer canonicalPageNumber(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Integer explicit = integer(metadata.get("pageNumber"));
        if (explicit != null) {
            return explicit;
        }
        Integer start = integer(metadata.get("pageStart"));
        Integer end = integer(metadata.get("pageEnd"));
        if (start == null && end == null) {
            return null;
        }
        if (start == null) {
            return end;
        }
        if (end == null || start.equals(end)) {
            return start;
        }
        return null;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
