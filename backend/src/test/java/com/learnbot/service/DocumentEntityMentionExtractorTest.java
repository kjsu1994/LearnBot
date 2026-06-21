package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentEntityMentionExtractorTest {
    private final DocumentEntityMentionExtractor extractor = new DocumentEntityMentionExtractor();

    @Test
    void extractsSatelliteGseEntityMentions() {
        assertThat(extractor.extract(
                "REQ-101 shall validate CMD-UPLINK and ERR-4001.",
                Map.of("schemaName", "SATELLITE_GSE", "documentType", "REQUIREMENT_SPEC")
        ))
                .extracting(DocumentEntityMentionExtractor.EntityMention::entityType)
                .contains("REQUIREMENT", "COMMAND", "ERROR_CODE");
    }

    @Test
    void returnsEmptyWhenProfileHasNoEntityPatterns() {
        assertThat(extractor.extract(
                "REQ-101 CMD-UPLINK",
                Map.of("schemaName", "CORE", "documentType", "GENERAL_DOCUMENT")
        )).isEmpty();
    }
}
