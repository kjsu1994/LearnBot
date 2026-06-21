package com.learnbot.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DocumentTypeClassifier {
    private final DocumentDomainProfileService domainProfileService;

    public DocumentTypeClassifier() {
        this(new DocumentDomainProfileService());
    }

    @Autowired
    public DocumentTypeClassifier(DocumentDomainProfileService domainProfileService) {
        this.domainProfileService = domainProfileService;
    }

    public Classification classify(ExtractedDocument document) {
        String text = (
                safe(document.title()) + " "
                        + safe(document.sourceUri()) + " "
                        + safe(document.contentType()) + " "
                        + safe(document.content())
        ).toLowerCase(Locale.ROOT);
        Map<String, List<String>> signals = domainProfileService.documentTypeSignals();
        String bestType = DocumentSchemaProfileService.GENERAL_DOCUMENT;
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : signals.entrySet()) {
            int score = 0;
            for (String signal : entry.getValue()) {
                if (text.contains(signal.toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestType = entry.getKey();
                bestScore = score;
            }
        }
        double confidence = bestScore == 0 ? 0.0 : Math.min(0.95, 0.45 + (bestScore * 0.15));
        return new Classification(bestType, confidence);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record Classification(String documentType, double confidence) {
    }
}
