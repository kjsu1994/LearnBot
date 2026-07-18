package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
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
    private static final int MAX_CLAIM_LINKED_SEARCH_HEAD_RANK = 3;

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
        Map<String, CodeEvidenceConstraint> constraints = new LinkedHashMap<>();
        Map<String, CodeEvidenceSignal> signals = new LinkedHashMap<>();

        for (CodeSearchResult result : context.evidence()) {
            List<CodeEvidenceOperationProvenance> provenance =
                    CodeEvidenceOperationProvenance.from(result);
            if (provenance.isEmpty()) continue;

            String evidenceId = CodeEvidenceItem.evidenceId(result);
            if (!candidates.containsKey(evidenceId) && candidates.size() >= limit) continue;

            boolean directObservation = provenance.stream()
                    .anyMatch(OperationEvidenceExtractor::isDirectObservation);
            boolean sourceBundleBoundary = provenance.stream()
                    .anyMatch(value -> isDirectObservation(value)
                            && "read_source_boundary".equals(value.operationType()));
            boolean claimLinkedSearchHead = provenance.stream()
                    .anyMatch(value -> isClaimLinkedSearchHead(result, value));
            CodeEvidenceItem item = new CodeEvidenceItem(
                    evidenceId,
                    result,
                    Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE),
                    EvidenceExtractionSupport.directSyntaxAuthority(result));
            candidates.merge(evidenceId,
                    new Candidate(item, directObservation, claimLinkedSearchHead),
                    OperationEvidenceExtractor::preferred);

            if (directObservation) {
                signals.putIfAbsent(evidenceId, new CodeEvidenceSignal(
                        sourceBundleBoundary
                                ? CodeEvidenceSignal.Type.SOURCE_BUNDLE_BOUNDARY
                                : CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                        evidenceId,
                        sourceBundleBoundary ? 0.9 : 1.0,
                        sourceBundleBoundary
                                ? "A bounded source bundle exposed a callable boundary through typed structural provenance."
                                : "A typed direct retrieval operation linked origin evidence to a structural source operand."));
            } else if (claimLinkedSearchHead) {
                signals.putIfAbsent(evidenceId, new CodeEvidenceSignal(
                        CodeEvidenceSignal.Type.CLAIM_LINKED_SEARCH_HEAD,
                        evidenceId,
                        0.8,
                        "A bounded head result from a typed claim-linked search exposed direct source evidence."));
            }
            boolean exactDirectProof = provenance.stream()
                    .anyMatch(value -> isExactDirectProof(result, value));
            if (exactDirectProof) {
                constraints.putIfAbsent(evidenceId, new CodeEvidenceConstraint(
                        CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED,
                        evidenceId,
                        "An exact typed source read resolved its requested structural operand."));
            }
        }

        return new CodeEvidenceIr(
                candidates.values().stream().map(Candidate::item).toList(),
                List.of(),
                List.copyOf(constraints.values()),
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

    private static boolean isClaimLinkedSearchHead(
            CodeSearchResult result,
            CodeEvidenceOperationProvenance provenance
    ) {
        return result != null
                && result.content() != null
                && !result.content().isBlank()
                && provenance != null
                && provenance.isClaimLinkedSearchResultHead(MAX_CLAIM_LINKED_SEARCH_HEAD_RANK);
    }

    /**
     * Promotes only fulfilled, structurally exact source reads to required proof.
     * Navigation operations remain useful observations but never become proof solely
     * because they were issued as direct operations.
     */
    private static boolean isExactDirectProof(
            CodeSearchResult result,
            CodeEvidenceOperationProvenance provenance
    ) {
        if (result == null || result.content() == null || result.content().isBlank()
                || !isDirectObservation(provenance)) {
            return false;
        }
        return switch (provenance.operationType()) {
            case "read_chunk" -> matchesChunk(result, provenance);
            case "read_symbol" -> matchesSymbolDefinition(result, provenance);
            case "read_file_range" -> matchesFileRange(result, provenance);
            default -> false;
        };
    }

    private static boolean matchesChunk(
            CodeSearchResult result,
            CodeEvidenceOperationProvenance provenance
    ) {
        return result.chunkId() != null
                && !provenance.chunkId().isBlank()
                && provenance.chunkId().equals(result.chunkId().toString());
    }

    private static boolean matchesSymbolDefinition(
            CodeSearchResult result,
            CodeEvidenceOperationProvenance provenance
    ) {
        if (!matchesPathWhenRequested(result, provenance.path())) return false;
        String requested = canonicalSymbol(provenance.symbol());
        if (requested.isBlank()) return false;

        String method = canonicalSymbol(result.methodName());
        String symbol = canonicalSymbol(result.symbolName());
        if (!requested.equals(method) && !requested.equals(symbol)) return false;

        String evidenceKind = EvidenceExtractionSupport.metadata(result, "symbolEvidenceKind");
        if ("REFERENCE".equalsIgnoreCase(evidenceKind)) return false;
        Object callableBody = EvidenceExtractionSupport.metadataValue(result, "callableBodyPresent");
        return callableBody == null || Boolean.parseBoolean(String.valueOf(callableBody));
    }

    private static boolean matchesFileRange(
            CodeSearchResult result,
            CodeEvidenceOperationProvenance provenance
    ) {
        if (!matchesPathWhenRequested(result, provenance.path())
                || provenance.lineStart() == null || provenance.lineEnd() == null) {
            return false;
        }
        return result.lineStart() <= provenance.lineEnd()
                && result.lineEnd() >= provenance.lineStart();
    }

    private static boolean matchesPathWhenRequested(CodeSearchResult result, String requestedPath) {
        String requested = normalizePath(requestedPath);
        return requested.isBlank() || requested.equals(normalizePath(result.filePath()));
    }

    private static String normalizePath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private static String canonicalSymbol(String value) {
        String canonical = value == null ? "" : value.trim();
        int parameters = canonical.indexOf('(');
        if (parameters >= 0) canonical = canonical.substring(0, parameters);
        canonical = canonical.replace("::", ".").replace('#', '.');
        int separator = canonical.lastIndexOf('.');
        if (separator >= 0 && separator + 1 < canonical.length()) {
            canonical = canonical.substring(separator + 1);
        }
        int generic = canonical.indexOf('<');
        return (generic > 0 ? canonical.substring(0, generic) : canonical).trim();
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
        if (incoming.claimLinkedSearchHead() != current.claimLinkedSearchHead()) {
            return incoming.claimLinkedSearchHead() ? incoming : current;
        }
        return incoming.item().authority().rank() > current.item().authority().rank()
                ? incoming : current;
    }

    private record Candidate(
            CodeEvidenceItem item,
            boolean directObservation,
            boolean claimLinkedSearchHead
    ) {
    }
}
