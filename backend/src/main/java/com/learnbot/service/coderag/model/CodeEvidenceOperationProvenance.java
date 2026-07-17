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
        Integer maxHops
) {
    public static final String METADATA_KEY = "codeEvidenceOperationProvenance";
    private static final int MAX_OPERAND_CHARS = 1_024;
    private static final Set<String> QUERY_OPERATION_TYPES = Set.of(
            "keyword_search", "hybrid_search", "reference_search", "find_endpoint");
    private static final Set<String> DIRECT_OPERATION_TYPES = Set.of(
            "read_chunk", "read_symbol", "list_file_symbols", "read_file_range",
            "read_adjacent", "traverse_graph");

    public CodeEvidenceOperationProvenance(
            String operationType,
            String operationId,
            List<String> claimIds,
            String evidenceGroup
    ) {
        this(operationType, operationId, claimIds, evidenceGroup, List.of(), "", "", "", "",
                null, null, null, List.of(), "", null);
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
    }

    public static List<CodeEvidenceOperationProvenance> from(CodeSearchResult result) {
        return result == null || result.metadata() == null
                ? List.of()
                : fromMetadata(result.metadata().get(METADATA_KEY));
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
                    integer(map.get("maxHops")));
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
