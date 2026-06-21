package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTypeClassifierTest {
    private final DocumentTypeClassifier classifier = new DocumentTypeClassifier();

    @Test
    void classifiesRequirementSpecificationSignals() {
        ExtractedDocument document = new ExtractedDocument(
                "Ground station requirement specification",
                "file:///gse-requirements.pdf",
                "application/pdf",
                "The system shall validate command parameters and telemetry requirements.",
                Map.of()
        );

        DocumentTypeClassifier.Classification classification = classifier.classify(document);

        assertThat(classification.documentType()).isEqualTo("REQUIREMENT_SPEC");
        assertThat(classification.confidence()).isGreaterThan(0.0);
    }

    @Test
    void fallsBackToGeneralDocumentWhenNoDomainSignalsExist() {
        ExtractedDocument document = new ExtractedDocument(
                "Meeting notes",
                "file:///notes.txt",
                "text/plain",
                "A short generic note without domain-specific graph schema signals.",
                Map.of()
        );

        DocumentTypeClassifier.Classification classification = classifier.classify(document);

        assertThat(classification.documentType()).isEqualTo(DocumentSchemaProfileService.GENERAL_DOCUMENT);
        assertThat(classification.confidence()).isZero();
    }
}
