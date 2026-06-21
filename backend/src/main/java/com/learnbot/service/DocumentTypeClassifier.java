package com.learnbot.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class DocumentTypeClassifier {
    public Classification classify(ExtractedDocument document) {
        String text = (
                safe(document.title()) + " "
                        + safe(document.sourceUri()) + " "
                        + safe(document.contentType()) + " "
                        + safe(document.content())
        ).toLowerCase(Locale.ROOT);
        Map<String, String[]> signals = Map.of(
                "REQUIREMENT_SPEC", new String[]{"requirement", "shall", "요구사항", "요건", "specification"},
                "DESIGN_SPEC", new String[]{"design", "architecture", "module", "설계", "구조"},
                "ICD", new String[]{"icd", "interface control", "interface", "command", "telemetry"},
                "TEST_PROCEDURE", new String[]{"test procedure", "procedure", "시험 절차", "검증 절차"},
                "TEST_RESULT", new String[]{"test result", "result", "시험 결과", "pass", "fail"},
                "OPERATION_MANUAL", new String[]{"operation manual", "manual", "운용", "사용자", "operator"},
                "TROUBLESHOOTING_GUIDE", new String[]{"troubleshooting", "fault", "error code", "장애", "오류", "조치"}
        );
        String bestType = DocumentSchemaProfileService.GENERAL_DOCUMENT;
        int bestScore = 0;
        for (Map.Entry<String, String[]> entry : signals.entrySet()) {
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
