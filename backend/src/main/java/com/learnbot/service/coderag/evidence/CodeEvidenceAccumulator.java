package com.learnbot.service.coderag.evidence;

import com.learnbot.service.coderag.evidence.extractor.EvidenceExtractorRegistry;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;

import com.learnbot.dto.CodeSearchResult;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Accumulates typed query-time evidence without mutating retrieval candidates.
 */
public final class CodeEvidenceAccumulator {
    private static final Set<String> MULTI_VALUE_PROVENANCE_KEYS = Set.of(
            "llmEvidenceCoverageGroup", "llmChecklistGroup", "llmReadEvidenceGroup", "llmValidatedEvidenceGroup",
            "llmFollowUpQuery", "llmSearchPlanQuery", "llmReadOperation", "llmReadArea",
            "llmChecklistClaimId", "llmChecklistGoal", "llmRequestedPath", "llmRequestedSymbol",
            "llmRequestedChunkId", "llmRequestedLineStart", "llmRequestedLineEnd", "llmRequestedRadius",
            "evidenceRankReason"
    );
    private static final Set<String> COLLECTION_PROVENANCE_KEYS = Set.of(
            "llmSupportedClaims", "llmNotSupportedClaims"
    );
    private static final Set<String> BOOLEAN_PROVENANCE_KEYS = Set.of(
            "llmFollowUpEvidence", "llmRetrievalIterationEvidence", "llmDirectRead", "llmReadFulfilled",
            "llmChecklistGroupRequired", "llmSearchPlanEvidence", "llmCoverageRequired", "llmValidatedEvidence",
            "deterministicEndpointEvidence", "deterministicEndpointCandidate", "deterministicEndpointBestMatch",
            "deterministicLexicalCandidate", "deterministicNavigationCandidate", "deterministicNavigationBestMatch"
    );
    private static final Set<String> STRUCTURAL_METADATA_KEYS = Set.of(
            "endpointRoute", "httpMethod", "graphRelation", "observedNavigationIdentifier"
    );

    private final EvidenceExtractorRegistry extractorRegistry;

    public CodeEvidenceAccumulator(EvidenceExtractorRegistry extractorRegistry) {
        this.extractorRegistry = Objects.requireNonNull(extractorRegistry, "extractorRegistry");
    }

    public Accumulation accumulate(CodeEvidenceExtractionContext context) {
        return accumulate(CodeEvidenceIr.empty(), context);
    }

    public Accumulation accumulate(CodeEvidenceIr current, CodeEvidenceExtractionContext context) {
        Objects.requireNonNull(context, "context");
        CodeEvidenceIr before = current == null ? CodeEvidenceIr.empty() : current;
        CodeEvidenceIr extracted = extractorRegistry.extract(context);
        CodeEvidenceIr merged = before.merge(extracted);
        return new Accumulation(
                merged,
                extracted,
                Math.max(0, merged.evidenceItems().size() - before.evidenceItems().size()),
                Math.max(0, merged.facts().size() - before.facts().size()),
                Math.max(0, merged.navigationHandles().size() - before.navigationHandles().size())
        );
    }

    /** Preserves all generic provenance while retaining the stronger candidate's scalar metadata. */
    public static Map<String, Object> mergeMetadata(
            CodeSearchResult preferred,
            CodeSearchResult current,
            CodeSearchResult incoming
    ) {
        Map<String, Object> preferredMetadata = metadataOf(preferred);
        Map<String, Object> currentMetadata = metadataOf(current);
        Map<String, Object> incomingMetadata = metadataOf(incoming);
        Map<String, Object> merged = new LinkedHashMap<>(preferredMetadata);
        Map<String, Object> otherMetadata = preferred == current ? incomingMetadata : currentMetadata;

        for (String key : MULTI_VALUE_PROVENANCE_KEYS) {
            LinkedHashSet<Object> values = new LinkedHashSet<>();
            addMetadataValues(values, preferredMetadata.get(key));
            addMetadataValues(values, otherMetadata.get(key));
            if (values.size() == 1) {
                merged.put(key, values.iterator().next());
            } else if (!values.isEmpty()) {
                merged.put(key, List.copyOf(values));
            }
        }
        for (String key : COLLECTION_PROVENANCE_KEYS) {
            LinkedHashSet<Object> values = new LinkedHashSet<>();
            addMetadataValues(values, preferredMetadata.get(key));
            addMetadataValues(values, otherMetadata.get(key));
            if (!values.isEmpty()) {
                merged.put(key, List.copyOf(values));
            }
        }
        for (String key : BOOLEAN_PROVENANCE_KEYS) {
            if (metadataFlag(currentMetadata.get(key)) || metadataFlag(incomingMetadata.get(key))) {
                merged.put(key, true);
            }
        }
        for (String key : STRUCTURAL_METADATA_KEYS) {
            Object preferredValue = merged.get(key);
            Object otherValue = otherMetadata.get(key);
            if ((preferredValue == null || String.valueOf(preferredValue).isBlank())
                    && otherValue != null && !String.valueOf(otherValue).isBlank()) {
                merged.put(key, otherValue);
            }
        }
        return Map.copyOf(merged);
    }

    private static Map<String, Object> metadataOf(CodeSearchResult result) {
        return result == null || result.metadata() == null ? Map.of() : result.metadata();
    }

    private static void addMetadataValues(Set<Object> values, Object raw) {
        if (raw instanceof Collection<?> collection) {
            collection.forEach(item -> addMetadataValues(values, item));
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            values.add(raw);
        }
    }

    private static boolean metadataFlag(Object raw) {
        return raw instanceof Boolean value ? value : raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    public record Accumulation(
            CodeEvidenceIr accumulated,
            CodeEvidenceIr extracted,
            int addedEvidenceItems,
            int addedFacts,
            int addedNavigationHandles
    ) {
        public Accumulation {
            accumulated = accumulated == null ? CodeEvidenceIr.empty() : accumulated;
            extracted = extracted == null ? CodeEvidenceIr.empty() : extracted;
            addedEvidenceItems = Math.max(0, addedEvidenceItems);
            addedFacts = Math.max(0, addedFacts);
            addedNavigationHandles = Math.max(0, addedNavigationHandles);
        }
    }
}
