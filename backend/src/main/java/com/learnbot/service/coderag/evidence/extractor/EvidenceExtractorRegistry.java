package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class EvidenceExtractorRegistry {
    private final List<EvidenceExtractor> extractors;

    public EvidenceExtractorRegistry(List<EvidenceExtractor> extractors) {
        this.extractors = extractors == null ? List.of() : extractors.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(EvidenceExtractor::priority).thenComparing(EvidenceExtractor::id))
                .toList();
        Set<String> ids = new LinkedHashSet<>();
        for (EvidenceExtractor extractor : this.extractors) {
            String id = extractor.id() == null ? "" : extractor.id().trim();
            if (id.isBlank() || !ids.add(id)) {
                throw new IllegalArgumentException("Evidence extractor ids must be non-blank and unique: " + id);
            }
        }
    }

    public List<EvidenceExtractor> extractors() {
        return extractors;
    }

    public List<EvidenceExtractor> extractorsFor(EvidenceExtractionStage stage) {
        if (stage == null) return List.of();
        return extractors.stream()
                .filter(extractor -> extractor.stages() != null && extractor.stages().contains(stage))
                .toList();
    }

    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        CodeEvidenceIr output = CodeEvidenceIr.empty();
        for (EvidenceExtractor extractor : extractorsFor(context.stage())) {
            try {
                if (!extractor.supports(context)) {
                    output = output.withDiagnostic(new CodeEvidenceIr.Diagnostic(
                            extractor.id(), CodeEvidenceIr.DiagnosticStatus.SKIPPED, "No applicable evidence."));
                    continue;
                }
                CodeEvidenceIr extracted = extractor.extract(context);
                if (extracted == null) {
                    throw new IllegalStateException("extractor returned null");
                }
                output = output.merge(extracted).withDiagnostic(new CodeEvidenceIr.Diagnostic(
                        extractor.id(), CodeEvidenceIr.DiagnosticStatus.SUCCESS,
                        "items=" + extracted.evidenceItems().size()
                                + ", facts=" + extracted.facts().size()
                                + ", handles=" + extracted.navigationHandles().size()));
            } catch (RuntimeException ex) {
                output = output.withDiagnostic(new CodeEvidenceIr.Diagnostic(
                        extractor.id(), CodeEvidenceIr.DiagnosticStatus.FAILED,
                        failureMessage(ex)));
            }
        }
        return output;
    }

    private String failureMessage(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return EvidenceExtractionSupport.truncate(message, 240);
    }
}
