package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class CodeEvidenceOperationExecutor {
    private static final int MAX_DIRECT_RESULTS = 24;
    private static final int MAX_LINE_SPAN = 400;
    private static final int MAX_RADIUS = 3;
    private static final int MAX_OBSERVATION_OPERAND_CHARS = 120;

    private final CodeSearchService searchService;
    private final CodeRepository repository;
    private final CodeReferenceService referenceService;

    CodeEvidenceOperationExecutor(
            CodeSearchService searchService,
            CodeRepository repository,
            CodeReferenceService referenceService
    ) {
        this.searchService = searchService;
        this.repository = repository;
        this.referenceService = referenceService;
    }

    Execution execute(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            GraphSearchIntent graphIntent,
            int limit
    ) {
        if (operation == null) {
            return Execution.invalid(null, "operation is required");
        }
        String validationError = operation.validationError();
        if (!validationError.isBlank()) {
            return Execution.invalid(operation, validationError);
        }
        int safeLimit = Math.max(1, Math.min(limit, MAX_DIRECT_RESULTS));
        try {
            List<CodeSearchResult> results = switch (operation.type()) {
                case "keyword_search" -> searchService.cheapSearch(
                        repositoryId, operation.query(), safeLimit, spaceIds, selectedSpaceId);
                case "hybrid_search" -> searchService.searchWithoutGraph(
                        repositoryId, operation.query(), safeLimit, spaceIds, selectedSpaceId, graphIntent);
                case "reference_search" -> {
                    var references = referenceService.findReferences(
                            repositoryId, selectedSpaceId, spaceIds, operation.query(), safeLimit);
                    yield java.util.stream.Stream.concat(
                            references.definitions().stream(), references.references().stream()).toList();
                }
                case "read_chunk" -> readChunk(repositoryId, selectedSpaceId, spaceIds, operation);
                case "read_symbol" -> readSymbol(repositoryId, selectedSpaceId, spaceIds, operation, safeLimit);
                case "list_file_symbols" -> listFileSymbols(repositoryId, selectedSpaceId, spaceIds, operation, safeLimit);
                case "read_file_range" -> readFileRange(repositoryId, selectedSpaceId, spaceIds, operation, safeLimit);
                case "read_adjacent" -> readAdjacent(repositoryId, selectedSpaceId, spaceIds, operation, safeLimit);
                case "traverse_graph" -> traverseGraph(repositoryId, selectedSpaceId, spaceIds, operation, safeLimit);
                default -> List.of();
            };
            List<CodeSearchResult> safeResults = results == null ? List.of() : results;
            List<CodeSearchResult> marked = operation.isDirectRead()
                    ? safeResults.stream().map(result -> markDirectRead(result, operation)).toList()
                    : safeResults;
            return marked.isEmpty()
                    ? new Execution(operation, "NOT_FOUND", List.of(), "operation returned no active evidence")
                    : new Execution(operation, "COMPLETED", marked, "");
        } catch (IllegalArgumentException ex) {
            return Execution.invalid(operation, safeMessage(ex));
        } catch (RuntimeException ex) {
            return new Execution(operation, "FAILED", List.of(), ex.getClass().getSimpleName() + ": " + safeMessage(ex));
        }
    }

    private List<CodeSearchResult> readChunk(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation
    ) {
        requireRepository();
        UUID chunkId = parseUuid(operation.chunkId(), "chunkId");
        return repository.findActiveChunksByIds(repositoryId, List.of(chunkId), spaceIds, selectedSpaceId);
    }

    private List<CodeSearchResult> readSymbol(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            int limit
    ) {
        requireRepository();
        String path = operation.path().isBlank() ? null : normalizeRelativePath(operation.path());
        String symbol = operation.symbol().trim();
        List<CodeSearchResult> definitions = repository.findSymbolDefinitions(
                repositoryId, symbol, path, limit, spaceIds, selectedSpaceId).stream()
                .map(result -> markSymbolEvidenceKind(result, "DEFINITION"))
                .toList();
        if (!definitions.isEmpty()) {
            return definitions.stream().limit(limit).toList();
        }
        List<CodeSearchResult> references = repository.findSymbolReferences(
                        repositoryId, symbol, limit, spaceIds, selectedSpaceId).stream()
                .filter(result -> path == null || path.equals(result.filePath()))
                .map(result -> markSymbolEvidenceKind(result, "REFERENCE"))
                .toList();
        return references.stream().limit(limit).toList();
    }

    private CodeSearchResult markSymbolEvidenceKind(CodeSearchResult result, String kind) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("symbolEvidenceKind", kind);
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score(), Map.copyOf(metadata));
    }

    private List<CodeSearchResult> readFileRange(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            int limit
    ) {
        requireRepository();
        String path = normalizeRelativePath(operation.path());
        int lineStart = positive(operation.lineStart(), "lineStart");
        int lineEnd = positive(operation.lineEnd(), "lineEnd");
        if (lineEnd < lineStart) {
            throw new IllegalArgumentException("lineEnd must be greater than or equal to lineStart");
        }
        if (lineEnd - lineStart + 1 > MAX_LINE_SPAN) {
            throw new IllegalArgumentException("requested line range exceeds " + MAX_LINE_SPAN + " lines");
        }
        int rangeReadLimit = Math.max(limit, 64);
        List<CodeSearchResult> initial = repository.findActiveChunksByPathAndLineRange(
                repositoryId, path, lineStart, lineEnd, rangeReadLimit, spaceIds, selectedSpaceId);
        int expandedStart = lineStart;
        int expandedEnd = lineEnd;
        for (CodeSearchResult result : initial) {
            if (result == null || result.lineStart() > lineEnd || result.lineEnd() < lineStart) {
                continue;
            }
            expandedStart = Math.min(expandedStart, result.lineStart());
            expandedEnd = Math.max(expandedEnd, result.lineEnd());
        }
        if (expandedEnd - expandedStart + 1 > MAX_LINE_SPAN) {
            expandedStart = lineStart;
            expandedEnd = lineStart + MAX_LINE_SPAN - 1;
        }
        if (expandedStart == lineStart && expandedEnd == lineEnd) {
            return initial;
        }
        List<CodeSearchResult> expanded = repository.findActiveChunksByPathAndLineRange(
                repositoryId, path, expandedStart, expandedEnd, rangeReadLimit, spaceIds, selectedSpaceId);
        return expanded.isEmpty() ? initial : expanded;
    }

    private List<CodeSearchResult> listFileSymbols(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            int limit
    ) {
        requireRepository();
        String path = normalizeRelativePath(operation.path());
        return repository.listActiveSymbolsByPath(
                repositoryId, path, Math.max(limit, MAX_DIRECT_RESULTS), spaceIds, selectedSpaceId);
    }

    private List<CodeSearchResult> readAdjacent(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            int limit
    ) {
        requireRepository();
        UUID chunkId = parseUuid(operation.chunkId(), "chunkId");
        int radius = operation.radius() == null ? 1 : operation.radius();
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius must be between 0 and " + MAX_RADIUS);
        }
        return repository.findAdjacentActiveChunks(
                repositoryId, chunkId, radius, limit, spaceIds, selectedSpaceId);
    }

    private List<CodeSearchResult> traverseGraph(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            int limit
    ) {
        requireRepository();
        UUID chunkId = parseUuid(operation.chunkId(), "chunkId");
        List<CodeSearchResult> seed = repository.findActiveChunksByIds(
                repositoryId, List.of(chunkId), spaceIds, selectedSpaceId);
        if (seed.isEmpty()) {
            return List.of();
        }
        int maxHops = operation.maxHops() == null ? 1 : operation.maxHops();
        List<CodeSearchResult> traversed = repository.graphRelatedChunks(
                repositoryId, List.of(chunkId), operation.relations(), maxHops,
                operation.direction(), limit);
        if (traversed.isEmpty()) {
            return List.of();
        }
        List<UUID> resultIds = traversed.stream().map(CodeSearchResult::chunkId).distinct().toList();
        var allowedIds = repository.findActiveChunksByIds(
                        repositoryId, resultIds, spaceIds, selectedSpaceId).stream()
                .map(CodeSearchResult::chunkId)
                .collect(java.util.stream.Collectors.toSet());
        return traversed.stream().filter(result -> allowedIds.contains(result.chunkId())).toList();
    }

    private CodeSearchResult markDirectRead(
            CodeSearchResult result,
            RagPipelineService.CodeSearchOperation operation
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("llmDirectRead", true);
        metadata.put("llmReadOperation", operation.type());
        metadata.put("llmReadArea", operation.area());
        metadata.put("llmReadEvidenceGroup", operation.evidenceGroup());
        metadata.put("llmReadFulfilled", true);
        putIfNotBlank(metadata, "llmRequestedPath", operation.path());
        putIfNotBlank(metadata, "llmRequestedSymbol", operation.symbol());
        putIfNotBlank(metadata, "llmRequestedChunkId", operation.chunkId());
        putIfNotNull(metadata, "llmRequestedLineStart", operation.lineStart());
        putIfNotNull(metadata, "llmRequestedLineEnd", operation.lineEnd());
        putIfNotNull(metadata, "llmRequestedRadius", operation.radius());
        metadata.put("actualLineStart", result.lineStart());
        metadata.put("actualLineEnd", result.lineEnd());
        metadata.put("contentComplete", true);
        if (!operation.evidenceGroup().isBlank()) {
            metadata.put("llmEvidenceCoverageGroup", operation.evidenceGroup());
            metadata.put("llmChecklistGroupRequired", true);
            metadata.put("llmChecklistGroup", operation.evidenceGroup());
        }
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score() + 0.25, Map.copyOf(metadata)
        );
    }

    private void requireRepository() {
        if (repository == null) {
            throw new IllegalArgumentException("direct-read repository is unavailable");
        }
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    private int positive(Integer value, String field) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(field + " must be at least 1");
        }
        return value;
    }

    private String normalizeRelativePath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("path must be repository-relative");
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("path traversal is not allowed");
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("path must be repository-relative");
        }
        return String.join("/", parts);
    }

    private void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                : ex.getMessage();
    }

    record Execution(
            RagPipelineService.CodeSearchOperation operation,
            String status,
            List<CodeSearchResult> results,
            String reason
    ) {
        Execution {
            status = status == null ? "FAILED" : status;
            results = results == null ? List.of() : List.copyOf(results);
            reason = reason == null ? "" : reason;
        }

        static Execution invalid(RagPipelineService.CodeSearchOperation operation, String reason) {
            return new Execution(operation, "INVALID", List.of(), reason);
        }

        String observation() {
            if (operation == null) {
                return "operation=unknown status=" + status + " reason=" + reason;
            }
            String evidence = results.stream().limit(4)
                    .map(result -> result.filePath() + ":" + result.lineStart() + "-" + result.lineEnd()
                            + (result.methodName() == null ? "" : "#" + result.methodName()))
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("none");
            return "type=" + operation.type()
                    + " area=" + operation.area()
                    + " evidenceGroup=" + operation.evidenceGroup()
                    + operationTarget(operation)
                    + " status=" + status
                    + " resultCount=" + results.size()
                    + " evidence=" + evidence
                    + (reason.isBlank() ? "" : " reason=" + reason);
        }

        private String operationTarget(RagPipelineService.CodeSearchOperation operation) {
            String target = switch (operation.type()) {
                case "keyword_search", "hybrid_search", "reference_search" ->
                        operand("query", operation.query());
                case "read_chunk" -> operand("chunkId", operation.chunkId());
                case "read_symbol" -> joinOperands(
                        operand("path", normalizedPath(operation.path())),
                        operand("symbol", operation.symbol()));
                case "list_file_symbols" -> operand("path", normalizedPath(operation.path()));
                case "read_file_range" -> joinOperands(
                        operand("path", normalizedPath(operation.path())),
                        numberOperand("lineStart", operation.lineStart()),
                        numberOperand("lineEnd", operation.lineEnd()));
                case "read_adjacent" -> joinOperands(
                        operand("chunkId", operation.chunkId()),
                        numberOperand("radius", operation.radius()));
                case "traverse_graph" -> joinOperands(
                        operand("chunkId", operation.chunkId()),
                        operand("relations", String.join("|", operation.relations())),
                        operand("direction", operation.direction()),
                        numberOperand("maxHops", operation.maxHops()));
                default -> "";
            };
            return target.isBlank() ? "" : " target={" + target + "}";
        }

        private String joinOperands(String... values) {
            return java.util.Arrays.stream(values)
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }

        private String operand(String name, String value) {
            String sanitized = sanitizeOperand(value);
            return sanitized.isBlank() ? "" : name + "=" + sanitized;
        }

        private String numberOperand(String name, Integer value) {
            return value == null ? "" : name + "=" + value;
        }

        private String normalizedPath(String value) {
            return value == null ? "" : value.replace('\\', '/');
        }

        private String sanitizeOperand(String value) {
            String compact = value == null ? "" : value
                    .replaceAll("[\\p{Cntrl}]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (compact.length() <= MAX_OBSERVATION_OPERAND_CHARS) {
                return compact;
            }
            return compact.substring(0, MAX_OBSERVATION_OPERAND_CHARS) + "...";
        }
    }
}
