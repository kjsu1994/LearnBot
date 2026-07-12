package com.learnbot.service;

import com.learnbot.dto.CodeAnalysisDiagnosticSummary;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.CodeSymbolOutline;
import com.learnbot.dto.IndexingJobFailureSummary;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.repository.CodeRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class RepositoryQuestionMapBuilder {
    private static final int MAX_PROJECT_CONTEXT = 8;
    private static final int MAX_INITIAL_EVIDENCE = 48;
    private static final int MAX_EVIDENCE = 80;
    private static final int MAX_RELATIONS = 24;
    private static final int MAX_DIAGNOSTICS = 12;
    private static final int MAX_FAILURES = 8;
    private static final int MAX_OBSERVATIONS = 16;
    private static final int MAX_PROMPT_CHARS = 14_000;
    private static final int MAX_INVENTORY_FILES = 16;
    private static final int MAX_SYMBOLS_PER_FILE = 240;
    private static final int MAX_MAP_NEIGHBORS_PER_UPDATE = 12;

    private final CodeRepository repository;

    RepositoryQuestionMapBuilder(CodeRepository repository) {
        this.repository = repository;
    }

    RepositoryQuestionMap build(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            Collection<CodeSearchResult> bootstrapCandidates
    ) {
        return build(repositoryId, selectedSpaceId, spaceIds, "", bootstrapCandidates);
    }

    RepositoryQuestionMap build(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            Collection<CodeSearchResult> bootstrapCandidates
    ) {
        List<CodeSearchResult> candidates = safeResults(bootstrapCandidates);
        ActiveCodeIndexIdentity identity = loadIdentity(repositoryId, selectedSpaceId, spaceIds, candidates);
        List<CodeSearchResult> projectContext = loadProjectContext(repositoryId, selectedSpaceId, spaceIds);
        LinkedHashMap<String, EvidenceEntry> evidence = new LinkedHashMap<>();
        addEvidence(evidence, projectContext, 0, "PROJECT_CONTEXT");
        addEvidence(evidence, candidates, 0, "BOOTSTRAP");
        RepositoryManifest manifest = loadManifest(repositoryId);
        Map<String, FileSymbolInventory> symbolInventories = loadSymbolInventories(
                identity, selectedSpaceId, spaceIds, evidence.values(), Map.of());
        List<RelationEvidence> relations = relationEvidence(evidence.values());
        List<CodeAnalysisDiagnosticSummary> diagnostics = loadDiagnostics(repositoryId, identity);
        List<IndexingJobFailureSummary> failures = loadFailures(repositoryId, identity);
        Set<String> added = new LinkedHashSet<>(evidence.keySet());
        relations.forEach(relation -> added.add(relation.evidenceId()));
        return new RepositoryQuestionMap(
                3,
                0,
                fingerprint(question),
                identity,
                manifest,
                symbolInventories,
                immutableEvidence(evidence),
                relations,
                diagnostics.stream().limit(MAX_DIAGNOSTICS).toList(),
                failures.stream().limit(MAX_FAILURES).toList(),
                List.of(),
                new MapDelta(-1, 0, List.copyOf(added), List.of(), false, !added.isEmpty())
        );
    }

    MapUpdateResult update(
            RepositoryQuestionMap current,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            Collection<CodeSearchResult> newCandidates,
            Collection<String> operationObservations
    ) {
        if (current == null) {
            throw new IllegalArgumentException("current repository map is required");
        }
        ActiveCodeIndexIdentity latest = loadIdentity(
                current.identity().repositoryId(), selectedSpaceId, spaceIds, safeResults(newCandidates));
        if (!sameSnapshot(current.identity(), latest)) {
            RepositoryQuestionMap reset = build(
                    current.identity().repositoryId(), selectedSpaceId, spaceIds, "", newCandidates);
            return new MapUpdateResult(reset, true);
        }

        List<CodeSearchResult> operationCandidates = safeResults(newCandidates);
        List<CodeSearchResult> mapNeighbors = loadMapNeighborhood(
                current.identity().repositoryId(), operationCandidates);
        LinkedHashMap<String, EvidenceEntry> evidence = new LinkedHashMap<>(current.evidence());
        LinkedHashSet<String> added = new LinkedHashSet<>();
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        for (CodeSearchResult result : java.util.stream.Stream.concat(
                operationCandidates.stream(), mapNeighbors.stream()).toList()) {
            if (!matchesIdentity(result, latest)) {
                continue;
            }
            String origin = operationCandidates.contains(result) ? "OPERATION" : "MAP_NEIGHBORHOOD";
            EvidenceEntry entry = evidenceEntry(result, current.revision() + 1, origin);
            EvidenceEntry previous = evidence.putIfAbsent(entry.evidenceId(), entry);
            if (previous == null) {
                added.add(entry.evidenceId());
            } else if (stronger(entry, previous)) {
                evidence.put(entry.evidenceId(), entry);
                updated.add(entry.evidenceId());
            }
        }
        evidence = retainStrongestEvidence(evidence);

        Map<String, FileSymbolInventory> symbolInventories = loadSymbolInventories(
                latest, selectedSpaceId, spaceIds, evidence.values(), current.symbolInventories());

        LinkedHashMap<String, RelationEvidence> relations = new LinkedHashMap<>();
        current.relations().forEach(relation -> relations.put(relation.evidenceId(), relation));
        for (RelationEvidence relation : relationEvidence(evidence.values())) {
            if (relations.putIfAbsent(relation.evidenceId(), relation) == null) {
                added.add(relation.evidenceId());
            }
        }

        List<String> allObservations = java.util.stream.Stream.concat(
                        current.observations().stream(),
                        operationObservations == null ? java.util.stream.Stream.empty() : operationObservations.stream())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        List<String> observations = allObservations.stream()
                .skip(Math.max(0, allObservations.size() - MAX_OBSERVATIONS))
                .toList();
        boolean progress = !added.isEmpty() || !updated.isEmpty();
        long nextRevision = current.revision() + 1;
        RepositoryQuestionMap next = new RepositoryQuestionMap(
                current.schemaVersion(),
                nextRevision,
                current.questionFingerprint(),
                latest,
                current.manifest(),
                symbolInventories,
                immutableEvidence(evidence),
                List.copyOf(relations.values()).stream().limit(MAX_RELATIONS).toList(),
                loadDiagnostics(latest.repositoryId(), latest).stream().limit(MAX_DIAGNOSTICS).toList(),
                loadFailures(latest.repositoryId(), latest).stream().limit(MAX_FAILURES).toList(),
                observations,
                new MapDelta(current.revision(), nextRevision, List.copyOf(added), List.copyOf(updated), false, progress)
        );
        return new MapUpdateResult(next, false);
    }

    private List<CodeSearchResult> loadMapNeighborhood(
            UUID repositoryId,
            List<CodeSearchResult> operationCandidates
    ) {
        if (repository == null || repositoryId == null || operationCandidates == null
                || operationCandidates.isEmpty()) {
            return List.of();
        }
        List<UUID> seedChunkIds = operationCandidates.stream()
                .filter(Objects::nonNull)
                .filter(result -> Boolean.TRUE.equals(metadata(result).get("llmDirectRead")))
                .map(CodeSearchResult::chunkId)
                .filter(Objects::nonNull)
                .distinct()
                .limit(8)
                .toList();
        if (seedChunkIds.isEmpty()) {
            return List.of();
        }
        try {
            return repository.graphRelatedChunks(
                    repositoryId, seedChunkIds, List.of(), 1, "BOTH", MAX_MAP_NEIGHBORS_PER_UPDATE);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private ActiveCodeIndexIdentity loadIdentity(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            List<CodeSearchResult> candidates
    ) {
        if (repository != null && repositoryId != null) {
            try {
                var identity = repository.findActiveIndexIdentity(repositoryId, spaceIds, selectedSpaceId);
                if (identity.isPresent()) {
                    return identity.get();
                }
            } catch (RuntimeException ex) {
                throw ex;
            }
        }
        String indexVersion = candidates.stream().map(CodeEvidenceId::indexVersion)
                .filter(value -> !value.isBlank()).findFirst().orElse(legacyActiveIndexVersion(repositoryId));
        return new ActiveCodeIndexIdentity(repositoryId, selectedSpaceId, parseUuid(indexVersion),
                "", "", "", "", "", "");
    }

    private String legacyActiveIndexVersion(UUID repositoryId) {
        if (repository == null || repositoryId == null) {
            return "";
        }
        try {
            for (IndexingJobSummary job : repository.listJobs(repositoryId)) {
                if (job != null && job.id() != null && repository.isActiveIndex(repositoryId, job.id())) {
                    return job.id().toString();
                }
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private List<CodeSearchResult> loadProjectContext(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds) {
        if (repository == null || repositoryId == null) {
            return List.of();
        }
        try {
            return repository.findActiveChunksByPath(
                    repositoryId, CodeProjectContextBuilder.CONTEXT_FILE_PATH,
                    MAX_PROJECT_CONTEXT, spaceIds, selectedSpaceId);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<CodeAnalysisDiagnosticSummary> loadDiagnostics(UUID repositoryId, ActiveCodeIndexIdentity identity) {
        if (repository == null || repositoryId == null || identity.indexVersion() == null) {
            return List.of();
        }
        try {
            return repository.listAnalysisDiagnostics(repositoryId, identity.indexVersion());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<IndexingJobFailureSummary> loadFailures(UUID repositoryId, ActiveCodeIndexIdentity identity) {
        if (repository == null || repositoryId == null || identity.indexVersion() == null) {
            return List.of();
        }
        try {
            return repository.listJobFailures(repositoryId, identity.indexVersion());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private RepositoryManifest loadManifest(UUID repositoryId) {
        if (repository == null || repositoryId == null) {
            return RepositoryManifest.empty();
        }
        try {
            List<ActiveCodeFileSnapshot> files = repository.listActiveFileSnapshots(repositoryId).values().stream()
                    .sorted(Comparator.comparing(ActiveCodeFileSnapshot::filePath))
                    .toList();
            LinkedHashMap<String, Integer> topLevelCounts = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> languageCounts = new LinkedHashMap<>();
            int chunkCount = 0;
            for (ActiveCodeFileSnapshot file : files) {
                String path = safe(file.filePath());
                String topLevel = path.contains("/") ? path.substring(0, path.indexOf('/')) : "<root>";
                topLevelCounts.merge(topLevel, 1, Integer::sum);
                languageCounts.merge(languageForPath(path), 1, Integer::sum);
                chunkCount += Math.max(0, file.chunkCount());
            }
            return new RepositoryManifest(files.size(), chunkCount, Map.copyOf(topLevelCounts),
                    Map.copyOf(languageCounts), files.stream().map(ActiveCodeFileSnapshot::filePath).toList());
        } catch (RuntimeException ignored) {
            return RepositoryManifest.empty();
        }
    }

    private Map<String, FileSymbolInventory> loadSymbolInventories(
            ActiveCodeIndexIdentity identity,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            Collection<EvidenceEntry> entries,
            Map<String, FileSymbolInventory> existing
    ) {
        LinkedHashMap<String, FileSymbolInventory> inventories = new LinkedHashMap<>(
                existing == null ? Map.of() : existing);
        if (repository == null || identity == null || identity.repositoryId() == null) {
            return Collections.unmodifiableMap(inventories);
        }
        List<String> paths = entries.stream()
                .filter(Objects::nonNull)
                .filter(entry -> !"PROJECT_CONTEXT".equals(entry.kind()))
                .filter(entry -> !entry.path().isBlank())
                .sorted(Comparator.comparingInt((EvidenceEntry entry) -> authorityRank(entry.authority())).reversed()
                        .thenComparing(Comparator.comparingDouble(EvidenceEntry::score).reversed()))
                .map(EvidenceEntry::path)
                .distinct()
                .filter(path -> !inventories.containsKey(path))
                .limit(MAX_INVENTORY_FILES)
                .toList();
        if (paths.isEmpty()) {
            return Collections.unmodifiableMap(inventories);
        }
        try {
            List<CodeSymbolOutline> outlines = repository.listActiveSymbolOutlinesByPaths(
                    identity.repositoryId(), paths, MAX_SYMBOLS_PER_FILE, spaceIds, selectedSpaceId);
            Map<String, List<CodeSymbolOutline>> byPath = outlines.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            CodeSymbolOutline::filePath, LinkedHashMap::new, java.util.stream.Collectors.toList()));
            for (String path : paths) {
                List<CodeSymbolOutline> symbols = byPath.getOrDefault(path, List.of());
                int total = symbols.stream().mapToInt(CodeSymbolOutline::totalInFile).max().orElse(0);
                String evidenceId = identity.indexVersion() + ":file-symbols:" +
                        Integer.toUnsignedString(path.hashCode(), 36);
                inventories.put(path, new FileSymbolInventory(
                        evidenceId, path, total, List.copyOf(symbols), symbols.size() >= total,
                        symbols.size() >= total ? "" : "symbols:" + path + ":" + symbols.size()));
            }
        } catch (RuntimeException ignored) {
            for (String path : paths) {
                inventories.putIfAbsent(path, new FileSymbolInventory(
                        "", path, 0, List.of(), false, "inventory-unavailable"));
            }
        }
        return Collections.unmodifiableMap(inventories);
    }

    private LinkedHashMap<String, EvidenceEntry> retainStrongestEvidence(Map<String, EvidenceEntry> entries) {
        LinkedHashMap<String, EvidenceEntry> retained = new LinkedHashMap<>();
        entries.values().stream()
                .sorted(Comparator.comparingInt((EvidenceEntry entry) -> authorityRank(entry.authority())).reversed()
                        .thenComparing(Comparator.comparingDouble(EvidenceEntry::score).reversed())
                        .thenComparing(Comparator.comparingLong(EvidenceEntry::discoveredRevision).reversed())
                        .thenComparing(EvidenceEntry::path)
                        .thenComparingInt(EvidenceEntry::lineStart))
                .limit(MAX_EVIDENCE)
                .forEach(entry -> retained.put(entry.evidenceId(), entry));
        return retained;
    }

    private String languageForPath(String path) {
        return CodeLanguageCatalog.languageForPath(path);
    }

    private void addEvidence(
            Map<String, EvidenceEntry> evidence,
            Collection<CodeSearchResult> candidates,
            long revision,
            String origin
    ) {
        List<CodeSearchResult> ranked = safeResults(candidates).stream()
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed()
                        .thenComparing(result -> safe(result.filePath()))
                        .thenComparingInt(CodeSearchResult::lineStart))
                .toList();
        for (CodeSearchResult result : ranked) {
            EvidenceEntry entry = evidenceEntry(result, revision, origin);
            evidence.putIfAbsent(entry.evidenceId(), entry);
            if (evidence.size() >= ("BOOTSTRAP".equals(origin) ? MAX_INITIAL_EVIDENCE : MAX_EVIDENCE)) {
                return;
            }
        }
    }

    private EvidenceEntry evidenceEntry(CodeSearchResult result, long revision, String origin) {
        String kind = evidenceKind(result, origin);
        String authority = authority(result, kind);
        return new EvidenceEntry(
                CodeEvidenceId.from(result), kind, authority,
                safe(result.filePath()), firstNonBlank(result.methodName(), result.symbolName(), result.className()),
                result.lineStart(), result.lineEnd(), result.chunkId(), result.score(), revision,
                origin, truncate(result.content(), "IMPLEMENTATION_BODY".equals(kind) ? 900 : 560), result
        );
    }

    private String evidenceKind(CodeSearchResult result, String origin) {
        if ("PROJECT_CONTEXT".equals(origin)) return "PROJECT_CONTEXT";
        if (Boolean.TRUE.equals(metadata(result).get("llmDirectRead"))) return "IMPLEMENTATION_BODY";
        Object symbolKind = metadata(result).get("symbolEvidenceKind");
        if ("DEFINITION".equals(symbolKind)) return "DEFINITION";
        if ("REFERENCE".equals(symbolKind)) return "LEXICAL_OCCURRENCE";
        if (firstNonBlank(result.methodName(), result.symbolName()).length() > 0) {
            return "IMPLEMENTATION_BODY";
        }
        return "NAVIGATION_HINT";
    }

    private String authority(CodeSearchResult result, String kind) {
        if ("PROJECT_CONTEXT".equals(kind) || "NAVIGATION_HINT".equals(kind)) return "NAVIGATION_ONLY";
        if ("LEXICAL_OCCURRENCE".equals(kind)) return "LEXICAL_OCCURRENCE";
        if (metadata(result).containsKey("graphEvidenceKind")) return "GRAPH_DERIVED";
        return "DIRECT_SOURCE";
    }

    private List<RelationEvidence> relationEvidence(Collection<EvidenceEntry> entries) {
        LinkedHashMap<String, RelationEvidence> relations = new LinkedHashMap<>();
        for (EvidenceEntry entry : entries) {
            Map<String, Object> metadata = metadata(entry.source());
            List<String> nodes = strings(metadata.get("graphPathNodes"));
            List<String> edges = strings(metadata.get("graphEdgeTypes"));
            int steps = Math.min(edges.size(), Math.max(0, nodes.size() - 1));
            for (int index = 0; index < steps && relations.size() < MAX_RELATIONS; index++) {
                String id = entry.evidenceId() + ":relation:" + index + ":" + edges.get(index);
                relations.putIfAbsent(id, new RelationEvidence(
                        id, nodes.get(index), edges.get(index), nodes.get(index + 1),
                        safe(metadata.get("graphDirection")), number(metadata.get("graphConfidence")),
                        safe(metadata.get("graphEvidenceKind")), entry.evidenceId()
                ));
            }
        }
        return List.copyOf(relations.values());
    }

    private boolean sameSnapshot(ActiveCodeIndexIdentity left, ActiveCodeIndexIdentity right) {
        return Objects.equals(left.repositoryId(), right.repositoryId())
                && Objects.equals(left.indexVersion(), right.indexVersion())
                && Objects.equals(left.contentFingerprint(), right.contentFingerprint())
                && Objects.equals(left.indexSchemaVersion(), right.indexSchemaVersion());
    }

    private boolean matchesIdentity(CodeSearchResult result, ActiveCodeIndexIdentity identity) {
        String candidateVersion = CodeEvidenceId.indexVersion(result);
        return identity.indexVersion() == null || candidateVersion.isBlank()
                || identity.indexVersion().toString().equals(candidateVersion);
    }

    private boolean stronger(EvidenceEntry candidate, EvidenceEntry previous) {
        return authorityRank(candidate.authority()) > authorityRank(previous.authority())
                || candidate.excerpt().length() > previous.excerpt().length();
    }

    private int authorityRank(String value) {
        return switch (value) {
            case "DIRECT_SOURCE" -> 4;
            case "GRAPH_DERIVED" -> 3;
            case "LEXICAL_OCCURRENCE" -> 2;
            default -> 1;
        };
    }

    private List<CodeSearchResult> safeResults(Collection<CodeSearchResult> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private static Map<String, EvidenceEntry> immutableEvidence(Map<String, EvidenceEntry> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private Map<String, Object> metadata(CodeSearchResult result) {
        return result == null || result.metadata() == null ? Map.of() : result.metadata();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<String> output = new ArrayList<>();
        for (Object item : collection) {
            if (item != null && !String.valueOf(item).isBlank()) output.add(String.valueOf(item).trim());
        }
        return List.copyOf(output);
    }

    private UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String fingerprint(String question) {
        return Integer.toUnsignedString(safe(question).trim().toLowerCase(java.util.Locale.ROOT).hashCode(), 36);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String truncate(String value, int limit) {
        String compact = safe(value).replaceAll("\\s+", " ");
        return compact.length() <= limit ? compact : compact.substring(0, limit) + "...";
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? 0.0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    record EvidenceEntry(
            String evidenceId, String kind, String authority, String path, String symbol,
            int lineStart, int lineEnd, UUID chunkId, double score, long discoveredRevision,
            String origin, String excerpt, CodeSearchResult source
    ) {
    }

    record RelationEvidence(
            String evidenceId, String from, String type, String to, String direction,
            double confidence, String authority, String sourceEvidenceId
    ) {
    }

    record RepositoryManifest(
            int fileCount,
            int chunkCount,
            Map<String, Integer> topLevelCounts,
            Map<String, Integer> languageCounts,
            List<String> activePaths
    ) {
        RepositoryManifest {
            topLevelCounts = topLevelCounts == null ? Map.of() : Map.copyOf(topLevelCounts);
            languageCounts = languageCounts == null ? Map.of() : Map.copyOf(languageCounts);
            activePaths = activePaths == null ? List.of() : List.copyOf(activePaths);
        }

        static RepositoryManifest empty() {
            return new RepositoryManifest(0, 0, Map.of(), Map.of(), List.of());
        }
    }

    record FileSymbolInventory(
            String evidenceId,
            String path,
            int totalCount,
            List<CodeSymbolOutline> symbols,
            boolean complete,
            String continuationHandle
    ) {
        FileSymbolInventory {
            evidenceId = evidenceId == null ? "" : evidenceId;
            path = path == null ? "" : path;
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            continuationHandle = continuationHandle == null ? "" : continuationHandle;
        }
    }

    record MapDelta(
            long fromRevision, long toRevision, List<String> addedEvidenceIds,
            List<String> updatedEvidenceIds, boolean reset, boolean evidenceProgress
    ) {
    }

    record MapUpdateResult(RepositoryQuestionMap map, boolean identityChanged) {
    }

    record RepositoryQuestionMap(
            int schemaVersion,
            long revision,
            String questionFingerprint,
            ActiveCodeIndexIdentity identity,
            RepositoryManifest manifest,
            Map<String, FileSymbolInventory> symbolInventories,
            Map<String, EvidenceEntry> evidence,
            List<RelationEvidence> relations,
            List<CodeAnalysisDiagnosticSummary> diagnostics,
            List<IndexingJobFailureSummary> failures,
            List<String> observations,
            MapDelta delta
    ) {
        RepositoryQuestionMap {
            questionFingerprint = questionFingerprint == null ? "" : questionFingerprint;
            manifest = manifest == null ? RepositoryManifest.empty() : manifest;
            symbolInventories = symbolInventories == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(symbolInventories));
            evidence = evidence == null ? Map.of() : immutableEvidence(evidence);
            relations = relations == null ? List.of() : List.copyOf(relations);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            failures = failures == null ? List.of() : List.copyOf(failures);
            observations = observations == null ? List.of() : List.copyOf(observations);
        }

        String indexVersion() {
            return identity == null || identity.indexVersion() == null ? "" : identity.indexVersion().toString();
        }

        boolean evidenceProgress() {
            return delta != null && delta.evidenceProgress();
        }

        boolean containsEvidenceId(String evidenceId) {
            if (evidenceId == null || evidenceId.isBlank()) return false;
            if (evidence.containsKey(evidenceId)) return true;
            if (symbolInventories.values().stream().anyMatch(inventory ->
                    evidenceId.equals(inventory.evidenceId())
                            || inventory.symbols().stream().anyMatch(symbol -> evidenceId.equals(symbol.entityId())))) return true;
            if (relations.stream().anyMatch(relation -> evidenceId.equals(relation.evidenceId()))) return true;
            if (diagnostics.stream().anyMatch(diagnostic ->
                    evidenceId.equals(indexVersion() + ":diagnostic:" + diagnostic.id()))) return true;
            return failures.stream().anyMatch(failure ->
                    evidenceId.equals(indexVersion() + ":failure:" + failure.id()));
        }

        boolean isDirectProofEvidenceId(String evidenceId) {
            if (evidenceId == null || evidenceId.isBlank()) return false;
            EvidenceEntry entry = evidence.get(evidenceId);
            if (entry != null) {
                return "IMPLEMENTATION_BODY".equals(entry.kind())
                        && "DIRECT_SOURCE".equals(entry.authority());
            }
            return relations.stream().anyMatch(relation -> evidenceId.equals(relation.evidenceId())
                    && ("DIRECT_SOURCE".equals(relation.authority())
                    || "GRAPH_DERIVED".equals(relation.authority())));
        }

        boolean observesPath(String path) {
            String expected = safe(path);
            return !expected.isBlank() && (manifest.activePaths().contains(expected)
                    || evidence.values().stream().anyMatch(entry -> expected.equals(entry.path()))
                    || symbolInventories.containsKey(expected));
        }

        boolean observesSymbol(String path, String symbol) {
            String expectedPath = safe(path);
            String expectedSymbol = safe(symbol);
            if (expectedSymbol.isBlank()) return false;
            return symbolInventories.values().stream()
                    .filter(inventory -> expectedPath.isBlank() || expectedPath.equals(inventory.path()))
                    .flatMap(inventory -> inventory.symbols().stream())
                    .anyMatch(outline -> expectedSymbol.equals(outline.name())
                            || expectedSymbol.equals(outline.qualifiedName()));
        }

        boolean observesChunk(String chunkId) {
            UUID expected;
            try {
                expected = UUID.fromString(safe(chunkId));
            } catch (IllegalArgumentException ignored) {
                return false;
            }
            return evidence.values().stream().anyMatch(entry -> expected.equals(entry.chunkId()))
                    || symbolInventories.values().stream().flatMap(inventory -> inventory.symbols().stream())
                    .anyMatch(symbol -> expected.equals(symbol.chunkId()));
        }

        boolean originSupportsPath(String evidenceId, String path) {
            String expected = safe(path);
            EvidenceEntry entry = evidence.get(evidenceId);
            if (entry != null) return expected.equals(entry.path());
            for (FileSymbolInventory inventory : symbolInventories.values()) {
                if (evidenceId.equals(inventory.evidenceId())) return expected.equals(inventory.path());
                if (inventory.symbols().stream().anyMatch(symbol -> evidenceId.equals(symbol.entityId()))) {
                    return expected.equals(inventory.path());
                }
            }
            return false;
        }

        String originEvidenceIdFor(RagPipelineService.CodeSearchOperation operation) {
            List<String> candidates = originEvidenceIdsFor(operation);
            return candidates.isEmpty() ? "" : candidates.get(0);
        }

        List<String> originEvidenceIdsFor(RagPipelineService.CodeSearchOperation operation) {
            if (operation == null || !operation.isDirectRead()) return List.of();
            String path = safe(operation.path());
            String symbol = safe(operation.symbol());
            String chunkId = safe(operation.chunkId());
            LinkedHashSet<String> candidates = new LinkedHashSet<>();

            FileSymbolInventory exactInventory = symbolInventories.get(path);
            if (exactInventory != null && !exactInventory.evidenceId().isBlank()) {
                boolean symbolMatches = symbol.isBlank() || exactInventory.symbols().stream().anyMatch(outline ->
                        symbol.equals(outline.name()) || symbol.equals(outline.qualifiedName()));
                boolean chunkMatches = chunkId.isBlank() || exactInventory.symbols().stream().anyMatch(outline ->
                        outline.chunkId() != null && chunkId.equals(outline.chunkId().toString()));
                if (symbolMatches && chunkMatches) candidates.add(exactInventory.evidenceId());
            }
            for (EvidenceEntry entry : evidence.values()) {
                boolean chunkMatches = !chunkId.isBlank() && entry.chunkId() != null
                        && chunkId.equals(entry.chunkId().toString());
                boolean symbolMatches = !symbol.isBlank() && path.equals(entry.path())
                        && symbol.equals(entry.symbol());
                boolean pathMatches = !path.isBlank() && symbol.isBlank() && chunkId.isBlank()
                        && path.equals(entry.path());
                if (chunkMatches || symbolMatches || pathMatches) candidates.add(entry.evidenceId());
            }
            for (FileSymbolInventory inventory : symbolInventories.values()) {
                if (!path.isBlank() && !path.equals(inventory.path())) continue;
                boolean matches = inventory.symbols().stream().anyMatch(outline ->
                        (!chunkId.isBlank() && outline.chunkId() != null && chunkId.equals(outline.chunkId().toString()))
                                || (!symbol.isBlank() && (symbol.equals(outline.name())
                                || symbol.equals(outline.qualifiedName()))));
                if (matches || (!path.isBlank() && symbol.isBlank() && chunkId.isBlank())) {
                    if (!inventory.evidenceId().isBlank()) candidates.add(inventory.evidenceId());
                }
            }
            return candidates.stream().sorted().toList();
        }

        String plannerContext() {
            StringBuilder output = new StringBuilder();
            output.append("RepositoryEvidenceMap schemaVersion=").append(schemaVersion)
                    .append(" revision=").append(revision)
                    .append(" repositoryId=").append(identity == null ? "" : identity.repositoryId())
                    .append(" indexVersion=").append(indexVersion().isBlank() ? "unknown" : indexVersion())
                    .append(" questionFingerprint=").append(questionFingerprint).append('\n');
            output.append("[REPOSITORY_MANIFEST] files=").append(manifest.fileCount())
                    .append(" chunks=").append(manifest.chunkCount())
                    .append(" languages=").append(manifest.languageCounts())
                    .append(" topLevel=").append(manifest.topLevelCounts()).append('\n');
            if (delta != null) {
                output.append("[MAP_DELTA] from=").append(delta.fromRevision())
                        .append(" to=").append(delta.toRevision())
                        .append(" added=").append(delta.addedEvidenceIds())
                        .append(" updated=").append(delta.updatedEvidenceIds())
                        .append(" progress=").append(delta.evidenceProgress()).append('\n');
            }
            appendSymbolInventories(output);
            appendEvidence(output, "DIRECT_BODIES_AND_DEFINITIONS", List.of("IMPLEMENTATION_BODY", "DEFINITION"), 6);
            appendEvidence(output, "PROJECT_CONTEXT", List.of("PROJECT_CONTEXT"), 4);
            appendEvidence(output, "REFERENCES_AND_NAVIGATION", List.of("LEXICAL_OCCURRENCE", "NAVIGATION_HINT"), 4);
            appendRecord(output, "\n[RELATIONS]\n");
            for (RelationEvidence relation : relations.stream().limit(MAX_RELATIONS).toList()) {
                appendRecord(output, "- evidenceId=" + relation.evidenceId() + " from=" + relation.from()
                        + " type=" + relation.type() + " to=" + relation.to()
                        + " direction=" + relation.direction() + " confidence=" + relation.confidence()
                        + " authority=" + relation.authority() + "\n");
            }
            appendRecord(output, "\n[ACTIVE_INDEX_DIAGNOSTICS]\n");
            for (CodeAnalysisDiagnosticSummary diagnostic : diagnostics.stream().limit(MAX_DIAGNOSTICS).toList()) {
                appendRecord(output, "- evidenceId=" + indexVersion() + ":diagnostic:" + diagnostic.id()
                        + " stage=" + safe(diagnostic.stage()) + " analyzer=" + safe(diagnostic.analyzer())
                        + " status=" + safe(diagnostic.status()) + " attempted=" + diagnostic.attemptedFiles()
                        + " analyzed=" + diagnostic.analyzedFiles() + " failed=" + diagnostic.failedFiles()
                        + " resolved=" + diagnostic.resolvedRelations() + " unresolved=" + diagnostic.unresolvedRelations()
                        + " message=" + truncate(diagnostic.message(), 240) + "\n");
            }
            appendRecord(output, "\n[ACTIVE_INDEX_FAILURES]\n");
            for (IndexingJobFailureSummary failure : failures.stream().limit(MAX_FAILURES).toList()) {
                appendRecord(output, "- evidenceId=" + indexVersion() + ":failure:" + failure.id()
                        + " stage=" + safe(failure.stage()) + " path=" + safe(failure.filePath())
                        + " message=" + truncate(failure.message(), 240) + "\n");
            }
            if (!observations.isEmpty()) {
                appendRecord(output, "\n[OPERATION_OBSERVATIONS]\n");
                for (String value : observations.stream().limit(MAX_OBSERVATIONS).toList()) {
                    appendRecord(output, "- " + truncate(value, 320) + "\n");
                }
            }
            return output.toString();
        }

        private void appendSymbolInventories(StringBuilder output) {
            output.append("\n[FILE_SYMBOL_INVENTORIES]\n");
            for (FileSymbolInventory inventory : symbolInventories.values()) {
                int remaining = Math.max(0, MAX_PROMPT_CHARS - output.length() - 280);
                List<String> symbolLines = new ArrayList<>();
                int used = 0;
                for (CodeSymbolOutline symbol : inventory.symbols()) {
                    String name = firstNonBlank(symbol.qualifiedName(), symbol.name());
                    String line = "  * " + safe(symbol.kind()) + " " + safe(name)
                            + "@" + symbol.lineStart() + "-" + symbol.lineEnd() + "\n";
                    if (used + line.length() > remaining) break;
                    symbolLines.add(line);
                    used += line.length();
                }
                boolean viewComplete = inventory.complete() && symbolLines.size() == inventory.symbols().size();
                String continuation = viewComplete ? "" : "map-view:" + inventory.path() + ":" + symbolLines.size();
                String authorities = inventory.symbols().stream().map(CodeSymbolOutline::authority)
                        .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList().toString();
                String analyzers = inventory.symbols().stream().map(CodeSymbolOutline::analyzer)
                        .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList().toString();
                String header = "- evidenceId=" + inventory.evidenceId() + " path=" + inventory.path()
                        + " shown=" + symbolLines.size() + " total=" + inventory.totalCount()
                        + " complete=" + viewComplete + " continuation=" + continuation
                        + " authorities=" + authorities + " analyzers=" + analyzers + "\n";
                if (!appendRecord(output, header)) return;
                for (String line : symbolLines) appendRecord(output, line);
                if (!viewComplete && !appendRecord(output,
                        "  * omittedByBudget=" + Math.max(0, inventory.totalCount() - symbolLines.size()) + "\n")) return;
            }
        }

        private boolean appendRecord(StringBuilder output, String value) {
            if (output.length() + value.length() > MAX_PROMPT_CHARS) return false;
            output.append(value);
            return true;
        }

        private void appendEvidence(StringBuilder output, String section, List<String> kinds, int limit) {
            output.append("\n[").append(section).append("]\n");
            List<EvidenceEntry> ranked = evidence.values().stream()
                    .filter(entry -> kinds.contains(entry.kind()))
                    .sorted(Comparator.comparingInt((EvidenceEntry entry) -> promptAuthorityRank(entry.authority())).reversed()
                            .thenComparing(Comparator.comparingDouble(EvidenceEntry::score).reversed())
                            .thenComparing(Comparator.comparingLong(EvidenceEntry::discoveredRevision).reversed())
                            .thenComparing(EvidenceEntry::path)
                            .thenComparingInt(EvidenceEntry::lineStart))
                    .toList();
            List<EvidenceEntry> selected = new ArrayList<>();
            Map<String, Integer> perPath = new LinkedHashMap<>();
            for (EvidenceEntry entry : ranked) {
                if (selected.size() >= limit) break;
                int count = perPath.getOrDefault(entry.path(), 0);
                if (count >= 3) continue;
                selected.add(entry);
                perPath.put(entry.path(), count + 1);
            }
            if (selected.size() < limit) {
                for (EvidenceEntry entry : ranked) {
                    if (selected.size() >= limit) break;
                    if (!selected.contains(entry)) selected.add(entry);
                }
            }
            for (EvidenceEntry entry : selected) {
                appendRecord(output, "- evidenceId=" + entry.evidenceId() + " kind=" + entry.kind()
                        + " authority=" + entry.authority() + " path=" + entry.path()
                        + " symbol=" + entry.symbol() + " lines=" + entry.lineStart() + '-' + entry.lineEnd()
                        + " chunkId=" + (entry.chunkId() == null ? "" : entry.chunkId())
                        + " origin=" + entry.origin() + " discoveredRevision=" + entry.discoveredRevision()
                        + "\n  excerpt=" + truncate(entry.excerpt(), 360) + "\n");
            }
        }

        private static int promptAuthorityRank(String authority) {
            return switch (authority == null ? "" : authority) {
                case "DIRECT_SOURCE" -> 4;
                case "GRAPH_DERIVED" -> 3;
                case "LEXICAL_OCCURRENCE" -> 2;
                default -> 1;
            };
        }
    }
}
