package com.learnbot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeIntelligenceRelationCatalogTest {
    @Test
    void separatesLanguageNeutralRelationsFromFrameworkExtensions() {
        assertThat(CodeIntelligenceRelationCatalog.core())
                .contains("CALLS", "REFERENCES", "IMPLEMENTS", "TRANSACTION_BOUNDARY")
                .doesNotContain("HANDLES_EVENT", "COMMAND_BINDING");
        assertThat(CodeIntelligenceRelationCatalog.extensions())
                .contains("HANDLES_EVENT", "COMMAND_BINDING");
        assertThat(CodeIntelligenceRelationCatalog.all())
                .containsAll(CodeIntelligenceRelationCatalog.core())
                .containsAll(CodeIntelligenceRelationCatalog.extensions());
    }
}
