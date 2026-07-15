package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;

import java.util.Set;

public interface EvidenceExtractor {
    String id();

    Set<EvidenceExtractionStage> stages();

    default int priority() {
        return 100;
    }

    default boolean supports(CodeEvidenceExtractionContext context) {
        return context != null && !context.evidence().isEmpty();
    }

    CodeEvidenceIr extract(CodeEvidenceExtractionContext context);
}
