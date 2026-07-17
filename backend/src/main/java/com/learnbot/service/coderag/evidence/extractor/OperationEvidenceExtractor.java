package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts typed retrieval-operation provenance into common evidence IR.
 *
 * <p>This extractor intentionally does not interpret planner query or area text. A retention signal
 * requires a typed direct operation with both an origin evidence handle and a structural operand.
 */
@Component
public final class OperationEvidenceExtractor implements EvidenceExtractor {

    @Override
    public String id() {
        return "operation";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_OPERATION, EvidenceExtractionStage.PRE_ANSWER);
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        return context != null
                && stages().contains(context.stage())
                && context.evidence().stream()
                .anyMatch(result -> !CodeEvidenceOperationProvenance.from(result).isEmpty());
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        if (!supports(context)) return CodeEvidenceIr.empty();

        int limit = context.maxItemsPerExtractor();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Map<String, CodeEvidenceSignal> signals = new LinkedHashMap<>();

        for (CodeSearchResult result : context.evidence()) {
            List<CodeEvidenceOperationProvenance> provenance =
                    CodeEvidenceOperationProvenance.from(result);
            if (provenance.isEmpty()) continue;

            String evidenceId = CodeEvidenceItem.evidenceId(result);
            if (!candidates.containsKey(evidenceId) && candidates.size() >= limit) continue;

            boolean directObservation = provenance.stream()
                    .anyMatch(OperationEvidenceExtractor::isDirectObservation);
            CodeEvidenceItem item = new CodeEvidenceItem(
                    evidenceId,
                    result,
                    Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE),
                    EvidenceExtractionSupport.directSyntaxAuthority(result));
            candidates.merge(evidenceId, new Candidate(item, directObservation),
                    OperationEvidenceExtractor::preferred);

            if (directObservation) {
                signals.putIfAbsent(evidenceId, new CodeEvidenceSignal(
                        CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                        evidenceId,
                        1.0,
                        "A typed direct retrieval operation linked origin evidence to a structural source operand."));
            }
        }

        return new CodeEvidenceIr(
                candidates.values().stream().map(Candidate::item).toList(),
                List.of(),
                List.of(),
                List.copyOf(signals.values()),
                List.of(),
                List.of());
    }

    private static boolean isDirectObservation(CodeEvidenceOperationProvenance provenance) {
        return provenance != null
                && !provenance.operationType().isBlank()
                && provenance.isDirectOperation()
                && !provenance.originEvidenceIds().isEmpty()
                && hasStructuralOperand(provenance);
    }

    private static boolean hasStructuralOperand(CodeEvidenceOperationProvenance provenance) {
        return !provenance.path().isBlank()
                || !provenance.symbol().isBlank()
                || !provenance.chunkId().isBlank()
                || (provenance.lineStart() != null && provenance.lineEnd() != null)
                || provenance.radius() != null
                || !provenance.relations().isEmpty();
    }

    private static Candidate preferred(Candidate current, Candidate incoming) {
        if (incoming.directObservation() != current.directObservation()) {
            return incoming.directObservation() ? incoming : current;
        }
        return incoming.item().authority().rank() > current.item().authority().rank()
                ? incoming : current;
    }

    private record Candidate(CodeEvidenceItem item, boolean directObservation) {
    }
}
