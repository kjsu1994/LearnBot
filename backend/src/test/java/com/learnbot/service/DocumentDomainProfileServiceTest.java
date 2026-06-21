package com.learnbot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDomainProfileServiceTest {
    private final DocumentDomainProfileService service = new DocumentDomainProfileService();

    @Test
    void resolvesExpectedDocumentTypesFromFallbackProfile() {
        assertThat(service.expectedDocumentTypes("ICD command telemetry parameter mapping"))
                .contains("ICD");
    }

    @Test
    void expandsProfileAwareQueries() {
        assertThat(service.expandedQueries("command telemetry interface"))
                .anySatisfy(query -> assertThat(query).contains("ICD").contains("telemetry"));
    }
}
