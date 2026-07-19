package com.learnbot.service.coderag.model;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Typed request-local provenance for evidence returned by a retrieval operation. */
public record CodeEvidenceOperationProvenance(
        String operationType,
        String operationId,
        List<String> claimIds,
        String evidenceGroup,
        List<String> originEvidenceIds,
        String query,
        String path,
        String symbol,
        String chunkId,
        Integer lineStart,
        Integer lineEnd,
        Integer radius,
        List<String> relations,
        String direction,
        Integer maxHops,
        Integer resultRank
) {
    public static final String METADATA_KEY = "codeEvidenceOperationProvenance";
    private static final int MAX_OPERAND_CHARS = 1_024;
    private static final Set<String> QUERY_OPERATION_TYPES = Set.of(
            "keyword_search", "hybrid_search", "reference_search", "find_endpoint");
    private static final Set<String> DIRECT_OPERATION_TYPES = Set.of(
            "read_chunk", "read_symbol", "list_file_symbols", "read_file_range",
            "read_adjacent", "traverse_graph", "read_source_member", "read_source_boundary");

    public CodeEvidenceOperationProvenance(
            String operationType,
            String operationId,
            List<String> claimIds,
            String evidenceGroup
    ) {
        this(operationType, operationId, claimIds, evidenceGroup, List.of(), "", "", "", "",
                null, null, null, List.of(), "", null, null);
    }

    public CodeEvidenceOperationProvenance(
            String operationType,
            String operationId,
            List<String> claimIds,
            String evidenceGroup,
            List<String> originEvidenceIds,
            String query,
            String path,
            String symbol,
            String chunkId,
            Integer lineStart,
            Integer lineEnd,
            Integer radius,
            List<String> relations,
            String direction,
            Integer maxHops
    ) {
        this(operationType, operationId, claimIds, evidenceGroup, originEvidenceIds, query,
                path, symbol, chunkId, lineStart, lineEnd, radius, relations, direction,
                maxHops, null);
    }

    public CodeEvidenceOperationProvenance {
        operationType = normalized(operationType);
        operationId = normalized(operationId);
        claimIds = normalizedValues(claimIds, 16, false);
        evidenceGroup = normalized(evidenceGroup);
        originEvidenceIds = normalizedValues(originEvidenceIds, 16, false);
        query = QUERY_OPERATION_TYPES.contains(operationType) ? bounded(query) : "";
        path = bounded(path).replace('\\', '/');
        symbol = bounded(symbol);
        chunkId = bounded(chunkId);
        relations = normalizedValues(relations, 8, true);
        direction = bounded(direction).toUpperCase(Locale.ROOT);
        resultRank = resultRank != null && resultRank > 0 ? resultRank : null;
    }

    public static List<CodeEvidenceOperationProvenance> from(CodeSearchResult result) {
        return result == null || result.metadata() == null
                ? List.of()
                : fromMetadata(result.metadata().get(METADATA_KEY));
    }

    /**
     * Returns whether the result is an implementation body reached through an explicitly bounded,
     * typed graph operation. Scalar graph metadata alone is intentionally insufficient: the
     * request-local operation provenance must identify the seed and allowed relation set.
     */
    public static boolean isBoundedGraphImplementation(CodeSearchResult result) {
        if (result == null || result.metadata() == null
                || !metadataBoolean(result, "graphExpanded")
                || result.methodName() == null || result.methodName().isBlank()
                || result.content() == null || result.content().isBlank()) {
            return false;
        }
        int depth = metadataInteger(result, "graphDepth", -1);
        String observedDirection = metadataText(result, "graphDirection");
        String observedEdge = metadataText(result, "graphEdgeType").toUpperCase(Locale.ROOT);
        if (depth < 1 || depth > 2 || observedDirection.isBlank() || observedEdge.isBlank()) {
            return false;
        }
        return from(result).stream()
                .filter(provenance -> "traverse_graph".equals(provenance.operationType()))
                .filter(provenance -> !provenance.originEvidenceIds().isEmpty())
                .anyMatch(provenance -> provenance.relations().isEmpty()
                        || provenance.relations().contains(observedEdge));
    }

    public static List<CodeEvidenceOperationProvenance> fromMetadata(Object raw) {
        LinkedHashSet<CodeEvidenceOperationProvenance> values = new LinkedHashSet<>();
        add(values, raw);
        return List.copyOf(values);
    }

    public static List<CodeEvidenceOperationProvenance> merge(Object left, Object right) {
        LinkedHashSet<CodeEvidenceOperationProvenance> values = new LinkedHashSet<>();
        add(values, left);
        add(values, right);
        return List.copyOf(values);
    }

    public boolean isSearchOperation() {
        return QUERY_OPERATION_TYPES.contains(operationType);
    }

    public boolean isDirectOperation() {
        return DIRECT_OPERATION_TYPES.contains(operationType);
    }

    /** A bounded callable chosen from the type inventory anchored by a typed search operation. */
    public boolean isSourceBundleCandidate() {
        return ("read_source_member".equals(operationType)
                || "read_source_boundary".equals(operationType))
                && !operationId.isBlank()
                && !claimIds.isEmpty()
                && !evidenceGroup.isBlank()
                && !originEvidenceIds.isEmpty()
                && !path.isBlank()
                && !symbol.isBlank();
    }

    /** A bounded search head is an exploration candidate, not exact proof. */
    public boolean isClaimLinkedSearchResultHead(int maxRank) {
        return maxRank > 0
                && isSearchOperation()
                && !operationId.isBlank()
                && !claimIds.isEmpty()
                && !evidenceGroup.isBlank()
                && !query.isBlank()
                && resultRank != null
                && resultRank <= maxRank;
    }

    /**
     * Identifies a server-anchored exact-read candidate before the result itself is adjudicated.
     * The operation shape earns IR preselection space; it does not prove that the returned source
     * fulfilled the operand.
     */
    public boolean isAnchoredExactReadCandidate() {
        if (originEvidenceIds.isEmpty()) return false;
        return switch (operationType) {
            case "read_chunk" -> !chunkId.isBlank();
            case "read_symbol" -> !symbol.isBlank() && (!path.isBlank() || !chunkId.isBlank());
            case "read_file_range" -> !path.isBlank() && lineStart != null && lineEnd != null;
            default -> false;
        };
    }

    private static void add(LinkedHashSet<CodeEvidenceOperationProvenance> target, Object raw) {
        if (raw instanceof CodeEvidenceOperationProvenance provenance) {
            if (!provenance.operationType().isBlank()) target.add(provenance);
            return;
        }
        if (raw instanceof Collection<?> collection) {
            collection.forEach(value -> add(target, value));
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                    value(map.get("operationType")),
                    value(map.get("operationId")),
                    strings(map.get("claimIds")),
                    value(map.get("evidenceGroup")),
                    strings(map.get("originEvidenceIds")),
                    value(map.get("query")),
                    value(map.get("path")),
                    value(map.get("symbol")),
                    value(map.get("chunkId")),
                    integer(map.get("lineStart")),
                    integer(map.get("lineEnd")),
                    integer(map.get("radius")),
                    strings(map.get("relations")),
                    value(map.get("direction")),
                    integer(map.get("maxHops")),
                    integer(map.get("resultRank")));
            if (!provenance.operationType().isBlank()) target.add(provenance);
        }
    }

    private static List<String> normalizedValues(List<String> values, int limit, boolean upperCase) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(CodeEvidenceOperationProvenance::bounded)
                .map(value -> upperCase ? value.toUpperCase(Locale.ROOT) : value)
                .distinct()
                .limit(limit)
                .toList();
    }

    private static List<String> strings(Object raw) {
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            collection.forEach(value -> {
                String text = value(value);
                if (!text.isBlank()) values.add(text);
            });
            return List.copyOf(values);
        }
        String value = value(raw);
        return value.isBlank() ? List.of() : List.of(value);
    }

    private static String value(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static Integer integer(Object raw) {
        if (raw instanceof Number number) return number.intValue();
        String value = value(raw);
        if (value.isBlank()) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String metadataText(CodeSearchResult result, String key) {
        Object raw = result == null || result.metadata() == null ? null : result.metadata().get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static boolean metadataBoolean(CodeSearchResult result, String key) {
        return Boolean.parseBoolean(metadataText(result, key));
    }

    private static int metadataInteger(CodeSearchResult result, String key, int fallback) {
        Object raw = result == null || result.metadata() == null ? null : result.metadata().get(key);
        if (raw instanceof Number number) return number.intValue();
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(String value) {
        String normalized = normalized(value);
        return normalized.length() <= MAX_OPERAND_CHARS
                ? normalized
                : normalized.substring(0, MAX_OPERAND_CHARS);
    }
}
