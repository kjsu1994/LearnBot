package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.ActiveCodeIndexIdentity;
import com.learnbot.service.ActiveCodeFileSnapshot;
import com.learnbot.service.CodeLanguageCatalog;
import com.learnbot.service.CodeProjectContextBuilder;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.evidence.CodeEvidenceId;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeNavigationHandle;

import com.learnbot.dto.CodeAnalysisDiagnosticSummary;
import com.learnbot.dto.CodeGraphRelationOutline;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RepositoryQuestionMapBuilder {
    private static final int MAX_PROJECT_CONTEXT = 8;
    private static final int MAX_INITIAL_EVIDENCE = 48;
    private static final int MAX_EVIDENCE = 80;
    private static final int MAX_RELATIONS = 36;
    private static final int MAX_RELATION_SEEDS = 16;
    private static final int MAX_RELATIONS_PER_SEED_QUERY = 64;
    private static final int MAX_NAVIGATION_HANDLES = 32;
    private static final int MAX_PRIMARY_NAVIGATION_HANDLES = 20;
    private static final int MAX_PROMPT_RELATION_HANDLES = 12;
    private static final int MAX_PRIMARY_RELATION_HANDLES = 6;
    private static final int MAX_DIAGNOSTICS = 12;
    private static final int MAX_FAILURES = 8;
    private static final int MAX_OBSERVATIONS = 16;
    private static final int MAX_PROMPT_CHARS = 14_000;
    private static final int MAX_IMPLEMENTATION_EXCERPT_CHARS = 1_200;
    private static final int MAX_INVENTORY_FILES = 16;
    private static final int MAX_SYMBOLS_PER_FILE = 240;
    private static final Pattern SEMANTIC_TOKEN = Pattern.compile("[\\p{L}\\p{N}_]{2,}");
    private final CodeRepository repository;

    public RepositoryQuestionMapBuilder(CodeRepository repository) {
        this.repository = repository;
    }

    public RepositoryQuestionMap build(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            Collection<CodeSearchResult> bootstrapCandidates
    ) {
        return build(repositoryId, selectedSpaceId, spaceIds, "", bootstrapCandidates, CodeEvidenceIr.empty());
    }

    public RepositoryQuestionMap build(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            Collection<CodeSearchResult> bootstrapCandidates
    ) {
        return build(repositoryId, selectedSpaceId, spaceIds, question, bootstrapCandidates, CodeEvidenceIr.empty());
    }

    public RepositoryQuestionMap build(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            Collection<CodeSearchResult> bootstrapCandidates,
            CodeEvidenceIr codeIntelligenceIr
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
        List<RelationEvidence> relations = retainRelations(question, java.util.stream.Stream.concat(
                        relationEvidence(evidence.values()).stream(),
                        loadIndexedRelations(identity, selectedSpaceId, spaceIds, evidence.values()).stream())
                .toList(), evidence);
        List<CodeAnalysisDiagnosticSummary> diagnostics = loadDiagnostics(repositoryId, identity);
        List<IndexingJobFailureSummary> failures = loadFailures(repositoryId, identity);
        Set<String> added = new LinkedHashSet<>(evidence.keySet());
        relations.forEach(relation -> added.add(relation.evidenceId()));
        return new RepositoryQuestionMap(
                4,
                0,
                fingerprint(question),
                identity,
                manifest,
                symbolInventories,
                immutableEvidence(evidence),
                codeIntelligenceIr == null ? CodeEvidenceIr.empty() : codeIntelligenceIr,
                relations,
                diagnostics.stream().limit(MAX_DIAGNOSTICS).toList(),
                failures.stream().limit(MAX_FAILURES).toList(),
                List.of(),
                new MapDelta(-1, 0, List.copyOf(added), List.of(), false, !added.isEmpty())
        );
    }

    public MapUpdateResult update(
            RepositoryQuestionMap current,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            Collection<CodeSearchResult> newCandidates,
            Collection<String> operationObservations
    ) {
        return update(current, selectedSpaceId, spaceIds, "", newCandidates, operationObservations,
                current == null ? CodeEvidenceIr.empty() : current.codeIntelligenceIr());
    }

    public MapUpdateResult update(
            RepositoryQuestionMap current,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            Collection<CodeSearchResult> newCandidates,
            Collection<String> operationObservations,
            CodeEvidenceIr codeIntelligenceIr
    ) {
        return update(current, selectedSpaceId, spaceIds, "", newCandidates, operationObservations,
                codeIntelligenceIr);
    }

    public MapUpdateResult update(
            RepositoryQuestionMap current,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            Collection<CodeSearchResult> newCandidates,
            Collection<String> operationObservations,
            CodeEvidenceIr codeIntelligenceIr
    ) {
        if (current == null) {
            throw new IllegalArgumentException("current repository map is required");
        }
        ActiveCodeIndexIdentity latest = loadIdentity(
                current.identity().repositoryId(), selectedSpaceId, spaceIds, safeResults(newCandidates));
        if (!sameSnapshot(current.identity(), latest)) {
            RepositoryQuestionMap reset = build(
                    current.identity().repositoryId(), selectedSpaceId, spaceIds, question, newCandidates,
                    CodeEvidenceIr.empty());
            return new MapUpdateResult(reset, true);
        }

        List<CodeSearchResult> operationCandidates = safeResults(newCandidates);
        LinkedHashMap<String, EvidenceEntry> evidence = new LinkedHashMap<>(current.evidence());
        LinkedHashSet<String> added = new LinkedHashSet<>();
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        for (CodeSearchResult result : operationCandidates) {
            if (!matchesIdentity(result, latest)) {
                continue;
            }
            EvidenceEntry entry = evidenceEntry(result, current.revision() + 1, "OPERATION");
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
        for (RelationEvidence relation : loadIndexedRelations(
                latest, selectedSpaceId, spaceIds, evidence.values())) {
            if (relations.putIfAbsent(relation.evidenceId(), relation) == null) {
                added.add(relation.evidenceId());
            }
        }
        List<RelationEvidence> retainedRelations = retainRelations(question, relations.values(), evidence);
        Set<String> retainedRelationIds = retainedRelations.stream()
                .map(RelationEvidence::evidenceId).collect(java.util.stream.Collectors.toSet());
        added.removeIf(id -> id.contains(":graph-relation:") && !retainedRelationIds.contains(id));

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
                current.codeIntelligenceIr().merge(codeIntelligenceIr),
                retainedRelations,
                loadDiagnostics(latest.repositoryId(), latest).stream().limit(MAX_DIAGNOSTICS).toList(),
                loadFailures(latest.repositoryId(), latest).stream().limit(MAX_FAILURES).toList(),
                observations,
                new MapDelta(current.revision(), nextRevision, List.copyOf(added), List.copyOf(updated), false, progress)
        );
        return new MapUpdateResult(next, false);
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
                .sorted(evidencePriority())
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
                .sorted(evidencePriority())
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
                origin, "IMPLEMENTATION_BODY".equals(kind)
                        ? implementationPlannerExcerpt(result.content())
                        : truncate(result.content(), 560), result
        );
    }

    /** Keeps bounded structural coverage of long callables without assuming a language or framework. */
    private String implementationPlannerExcerpt(String value) {
        String content = safe(value).trim();
        if (content.length() <= MAX_IMPLEMENTATION_EXCERPT_CHARS) return content;
        String middleMarker = "\n... [middle excerpt] ...\n";
        String tailMarker = "\n... [tail excerpt] ...\n";
        int segment = Math.max(1, (MAX_IMPLEMENTATION_EXCERPT_CHARS
                - middleMarker.length() - tailMarker.length()) / 3);
        int middleStart = Math.max(0, (content.length() - segment) / 2);
        int tailStart = Math.max(0, content.length() - segment);
        return content.substring(0, segment)
                + middleMarker
                + content.substring(middleStart, Math.min(content.length(), middleStart + segment))
                + tailMarker
                + content.substring(tailStart);
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
                        safe(metadata.get("graphEvidenceKind")), entry.evidenceId(),
                        entry.path(), entry.chunkId(), "", null, false, entry.score()
                ));
            }
        }
        return List.copyOf(relations.values());
    }

    private List<RelationEvidence> loadIndexedRelations(
            ActiveCodeIndexIdentity identity,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            Collection<EvidenceEntry> entries
    ) {
        if (repository == null || identity == null || identity.repositoryId() == null
                || identity.indexVersion() == null || entries == null || entries.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<UUID, EvidenceEntry> sources = new LinkedHashMap<>();
        entries.stream()
                .filter(Objects::nonNull)
                .filter(entry -> entry.chunkId() != null)
                .filter(entry -> !"PROJECT_CONTEXT".equals(entry.kind()))
                .sorted(evidencePriority())
                .forEach(entry -> sources.putIfAbsent(entry.chunkId(), entry));
        List<UUID> seedChunkIds = sources.keySet().stream().limit(MAX_RELATION_SEEDS).toList();
        if (seedChunkIds.isEmpty()) return List.of();
        try {
            return repository.listActiveGraphRelationOutlinesByChunkIds(
                            identity.repositoryId(), identity.indexVersion(), seedChunkIds,
                            MAX_RELATIONS_PER_SEED_QUERY, spaceIds, selectedSpaceId).stream()
                    .map(outline -> indexedRelation(identity, sources.get(outline.seedChunkId()), outline))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private RelationEvidence indexedRelation(
            ActiveCodeIndexIdentity identity,
            EvidenceEntry source,
            CodeGraphRelationOutline outline
    ) {
        if (source == null || outline == null || outline.edgeId() == null
                || safe(outline.relationType()).isBlank()) return null;
        String evidenceId = identity.indexVersion() + ":graph-relation:" + outline.edgeId();
        return new RelationEvidence(
                evidenceId,
                firstNonBlank(outline.seedName(), outline.seedQualifiedName()),
                safe(outline.relationType()).toUpperCase(java.util.Locale.ROOT),
                firstNonBlank(outline.neighborName(), outline.neighborQualifiedName()),
                safe(outline.direction()).toUpperCase(java.util.Locale.ROOT),
                Math.max(0.0, Math.min(1.0, outline.confidence())),
                "NAVIGATION_ONLY",
                source.evidenceId(),
                safe(outline.seedPath()),
                outline.seedChunkId(),
                safe(outline.neighborPath()),
                outline.neighborChunkId(),
                true,
                source.score()
        );
    }

    private List<RelationEvidence> retainRelations(
            String question,
            Collection<RelationEvidence> values,
            Map<String, EvidenceEntry> evidence
    ) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashMap<String, RelationEvidence> unique = new LinkedHashMap<>();
        values.stream().filter(Objects::nonNull)
                .forEach(relation -> unique.putIfAbsent(relation.evidenceId(), relation));
        Set<String> questionTokens = semanticTokens(question);
        Comparator<RelationEvidence> ranking = Comparator
                .comparingInt((RelationEvidence relation) -> relationOperationRank(relation, evidence)).reversed()
                .thenComparing(Comparator.comparingLong(
                        (RelationEvidence relation) -> relationRevision(relation, evidence)).reversed())
                .thenComparing(Comparator.comparingInt(
                        (RelationEvidence relation) -> relationRelevance(
                                relationIntentTokens(questionTokens, relation, evidence), relation)).reversed())
                .thenComparing(Comparator.comparingDouble(RelationEvidence::sourceScore).reversed())
                .thenComparing((RelationEvidence relation) -> relation.toChunkId() == null)
                .thenComparing(Comparator.comparingDouble(RelationEvidence::confidence).reversed())
                .thenComparing(RelationEvidence::fromPath)
                .thenComparing(RelationEvidence::type)
                .thenComparing(RelationEvidence::to);
        List<RelationEvidence> ranked = unique.values().stream().sorted(ranking).toList();
        LinkedHashMap<String, RelationEvidence> capabilityRepresentatives = new LinkedHashMap<>();
        for (RelationEvidence relation : ranked) {
            String capability = String.join("|", relation.sourceEvidenceId(), relation.direction(), relation.type());
            capabilityRepresentatives.putIfAbsent(capability, relation);
        }
        LinkedHashMap<String, RelationEvidence> selected = new LinkedHashMap<>();
        capabilityRepresentatives.values().stream().sorted(ranking).limit(MAX_RELATIONS)
                .forEach(relation -> selected.put(relation.evidenceId(), relation));
        if (selected.size() < MAX_RELATIONS) {
            for (RelationEvidence relation : ranked) {
                selected.putIfAbsent(relation.evidenceId(), relation);
                if (selected.size() >= MAX_RELATIONS) break;
            }
        }
        return List.copyOf(selected.values());
    }

    private int relationOperationRank(
            RelationEvidence relation,
            Map<String, EvidenceEntry> evidence
    ) {
        EvidenceEntry source = relation == null || evidence == null
                ? null : evidence.get(relation.sourceEvidenceId());
        return operationProofRank(source);
    }

    private long relationRevision(
            RelationEvidence relation,
            Map<String, EvidenceEntry> evidence
    ) {
        EvidenceEntry source = relation == null || evidence == null
                ? null : evidence.get(relation.sourceEvidenceId());
        return source == null ? -1L : source.discoveredRevision();
    }

    private Set<String> relationIntentTokens(
            Set<String> questionTokens,
            RelationEvidence relation,
            Map<String, EvidenceEntry> evidence
    ) {
        LinkedHashSet<String> intent = new LinkedHashSet<>(
                questionTokens == null ? Set.of() : questionTokens);
        EvidenceEntry source = relation == null || evidence == null
                ? null : evidence.get(relation.sourceEvidenceId());
        if (source == null) return Set.copyOf(intent);
        for (CodeEvidenceOperationProvenance provenance
                : CodeEvidenceOperationProvenance.from(source.source())) {
            intent.addAll(semanticTokens(String.join(" ",
                    provenance.query(), provenance.evidenceGroup(), provenance.symbol())));
        }
        return Set.copyOf(intent);
    }

    private int relationRelevance(Set<String> questionTokens, RelationEvidence relation) {
        if (questionTokens == null || questionTokens.isEmpty() || relation == null) return 0;
        Set<String> relationTokens = semanticTokens(String.join(" ", relation.from(), relation.to(),
                relation.fromPath(), relation.toPath(), relation.type()));
        int matches = 0;
        for (String questionToken : questionTokens) {
            if (relationTokens.stream().anyMatch(token -> navigationTokenMatch(questionToken, token))) matches++;
        }
        return matches;
    }

    private Set<String> semanticTokens(String value) {
        String split = safe(value)
                .replaceAll("([\\p{Ll}\\p{N}])([\\p{Lu}])", "$1 $2")
                .replaceAll("[^\\p{L}\\p{N}_]+", " ")
                .toLowerCase(java.util.Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = SEMANTIC_TOKEN.matcher(split);
        while (matcher.find()) tokens.add(matcher.group());
        return Set.copyOf(tokens);
    }

    private boolean navigationTokenMatch(String left, String right) {
        if (left.equals(right)) return true;
        if (left.codePointCount(0, left.length()) < 3 || right.codePointCount(0, right.length()) < 3) return false;
        return left.startsWith(right) || right.startsWith(left);
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
        int authority = Integer.compare(
                authorityRank(candidate.authority()), authorityRank(previous.authority()));
        if (authority != 0) return authority > 0;
        int operationProof = Integer.compare(
                operationProofRank(candidate), operationProofRank(previous));
        if (operationProof != 0) return operationProof > 0;
        int kind = Integer.compare(evidenceKindRank(candidate.kind()), evidenceKindRank(previous.kind()));
        if (kind != 0) return kind > 0;
        int excerpt = Integer.compare(candidate.excerpt().length(), previous.excerpt().length());
        if (excerpt != 0) return excerpt > 0;
        int score = Double.compare(candidate.score(), previous.score());
        if (score != 0) return score > 0;
        return candidate.discoveredRevision() > previous.discoveredRevision()
                && "OPERATION".equals(candidate.origin())
                && !"OPERATION".equals(previous.origin());
    }

    /** Exact typed reads outrank navigation inventories and search hits for planner-map retention. */
    private static int operationProofRank(EvidenceEntry entry) {
        Map<String, Object> sourceMetadata = metadata(entry == null ? null : entry.source());
        int typedRank = CodeEvidenceOperationProvenance.from(entry == null ? null : entry.source()).stream()
                .mapToInt(provenance -> switch (provenance.operationType()) {
                    case "read_chunk", "read_symbol", "read_file_range" -> 6;
                    case "read_adjacent", "traverse_graph" -> 5;
                    case "list_file_symbols" -> 4;
                    default -> provenance.isSearchOperation() ? 2 : 1;
                })
                .max()
                .orElse(0);
        if (typedRank > 0) return typedRank;
        if (metadataFlag(sourceMetadata, "llmDirectRead")) return 4;
        if (metadataFlag(sourceMetadata, "llmReadFulfilled")) return 3;
        if (metadataFlag(sourceMetadata, "llmRetrievalIterationEvidence")) return 2;
        return entry != null && "OPERATION".equals(entry.origin()) ? 1 : 0;
    }

    private static Comparator<EvidenceEntry> evidencePriority() {
        return Comparator
                .comparingInt((EvidenceEntry entry) -> authorityRank(entry.authority())).reversed()
                .thenComparing(Comparator.comparingInt(
                        RepositoryQuestionMapBuilder::operationProofRank).reversed())
                .thenComparing(Comparator.comparingLong(EvidenceEntry::discoveredRevision).reversed())
                .thenComparing(Comparator.comparingInt(
                        (EvidenceEntry entry) -> evidenceKindRank(entry.kind())).reversed())
                .thenComparing(Comparator.comparingDouble(EvidenceEntry::score).reversed())
                .thenComparing(EvidenceEntry::path)
                .thenComparingInt(EvidenceEntry::lineStart);
    }

    private static boolean metadataFlag(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Boolean flag ? flag
                : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static int evidenceKindRank(String value) {
        return switch (value == null ? "" : value) {
            case "IMPLEMENTATION_BODY" -> 4;
            case "DEFINITION" -> 3;
            case "LEXICAL_OCCURRENCE" -> 2;
            case "NAVIGATION_HINT" -> 1;
            default -> 0;
        };
    }

    private static int authorityRank(String value) {
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

    private static Map<String, Object> metadata(CodeSearchResult result) {
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
            double confidence, String authority, String sourceEvidenceId,
            String fromPath, UUID fromChunkId, String toPath, UUID toChunkId,
            boolean navigationOnly, double sourceScore
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

    public record MapUpdateResult(RepositoryQuestionMap map, boolean identityChanged) {
    }

    public record RepositoryQuestionMap(
            int schemaVersion,
            long revision,
            String questionFingerprint,
            ActiveCodeIndexIdentity identity,
            RepositoryManifest manifest,
            Map<String, FileSymbolInventory> symbolInventories,
            Map<String, EvidenceEntry> evidence,
            CodeEvidenceIr codeIntelligenceIr,
            List<RelationEvidence> relations,
            List<CodeAnalysisDiagnosticSummary> diagnostics,
            List<IndexingJobFailureSummary> failures,
            List<String> observations,
            MapDelta delta
    ) {
        public RepositoryQuestionMap {
            questionFingerprint = questionFingerprint == null ? "" : questionFingerprint;
            manifest = manifest == null ? RepositoryManifest.empty() : manifest;
            symbolInventories = symbolInventories == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(symbolInventories));
            evidence = evidence == null ? Map.of() : immutableEvidence(evidence);
            codeIntelligenceIr = codeIntelligenceIr == null ? CodeEvidenceIr.empty() : codeIntelligenceIr;
            relations = relations == null ? List.of() : List.copyOf(relations);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            failures = failures == null ? List.of() : List.copyOf(failures);
            observations = observations == null ? List.of() : List.copyOf(observations);
        }

        public String indexVersion() {
            return identity == null || identity.indexVersion() == null ? "" : identity.indexVersion().toString();
        }

        public boolean evidenceProgress() {
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

        public boolean isDirectProofEvidenceId(String evidenceId) {
            if (evidenceId == null || evidenceId.isBlank()) return false;
            EvidenceEntry entry = evidence.get(evidenceId);
            if (entry != null) {
                return "IMPLEMENTATION_BODY".equals(entry.kind())
                        && "DIRECT_SOURCE".equals(entry.authority())
                        && isConcreteImplementationSpan(entry.source());
            }
            return relations.stream().anyMatch(relation -> evidenceId.equals(relation.evidenceId())
                    && ("DIRECT_SOURCE".equals(relation.authority())
                    || "GRAPH_DERIVED".equals(relation.authority())));
        }

        /** Only a callable identity is concrete behavior proof; other symbols remain navigation or typed-IR input. */
        private boolean isConcreteImplementationSpan(CodeSearchResult source) {
            if (source == null) return false;
            String container = canonicalSymbol(source.className());
            String method = canonicalSymbol(source.methodName());
            if (method.isBlank()) return false;
            if (!hasConcreteCallableBody(source)) return false;
            if (container.isBlank() || !method.equalsIgnoreCase(container)) return true;
            String callableKind = safe(source.chunkType()).trim();
            return "method".equalsIgnoreCase(callableKind)
                    || "constructor".equalsIgnoreCase(callableKind);
        }

        private boolean hasConcreteCallableBody(CodeSearchResult source) {
            Object declared = source.metadata() == null
                    ? null : source.metadata().get("callableBodyPresent");
            if (declared != null) return Boolean.parseBoolean(String.valueOf(declared));
            String content = safe(source.content());
            if (content.contains("{") || content.contains("=>")) return true;
            List<String> lines = content.lines().map(String::stripTrailing).toList();
            for (int index = 0; index + 1 < lines.size(); index++) {
                if (lines.get(index).stripTrailing().endsWith(":")) {
                    return lines.subList(index + 1, lines.size()).stream().anyMatch(line -> !line.isBlank());
                }
            }
            return false;
        }

        private String canonicalSymbol(String value) {
            String symbol = safe(value).trim();
            int parameters = symbol.indexOf('(');
            if (parameters >= 0) symbol = symbol.substring(0, parameters);
            symbol = symbol.replace("::", ".").replace('#', '.');
            int separator = symbol.lastIndexOf('.');
            return separator >= 0 ? symbol.substring(separator + 1) : symbol;
        }

        boolean observesPath(String path) {
            String expected = safe(path);
            return !expected.isBlank() && (manifest.activePaths().contains(expected)
                    || evidence.values().stream().anyMatch(entry -> expected.equals(entry.path()))
                    || symbolInventories.containsKey(expected)
                    || relations.stream().anyMatch(relation ->
                    expected.equals(relation.fromPath()) || expected.equals(relation.toPath())));
        }

        boolean observesSymbol(String path, String symbol) {
            String expectedPath = safe(path);
            String expectedSymbol = safe(symbol);
            if (expectedSymbol.isBlank()) return false;
            boolean inventoryMatch = symbolInventories.values().stream()
                    .filter(inventory -> expectedPath.isBlank() || expectedPath.equals(inventory.path()))
                    .flatMap(inventory -> inventory.symbols().stream())
                    .anyMatch(outline -> sameNavigationSymbol(expectedSymbol, outline.name())
                            || sameNavigationSymbol(expectedSymbol, outline.qualifiedName()));
            boolean relationMatch = relations.stream().anyMatch(relation ->
                    relationEndpointMatches(relation.fromPath(), relation.from(), expectedPath, expectedSymbol)
                            || relationEndpointMatches(relation.toPath(), relation.to(), expectedPath, expectedSymbol));
            return inventoryMatch || relationMatch || expectedPath.isBlank() && navigationHandles().stream()
                    .filter(handle -> handle.kind() == CodeNavigationHandle.Kind.CALL)
                    .anyMatch(handle -> sameNavigationSymbol(handle.symbol(), expectedSymbol));
        }

        private boolean relationEndpointMatches(
                String endpointPath,
                String endpointSymbol,
                String expectedPath,
                String expectedSymbol
        ) {
            return (expectedPath.isBlank() || expectedPath.equals(endpointPath))
                    && sameNavigationSymbol(expectedSymbol, endpointSymbol);
        }

        boolean observesCallFromPath(String path, String symbol) {
            String expectedPath = safe(path);
            String expectedSymbol = safe(symbol);
            return !expectedPath.isBlank() && !expectedSymbol.isBlank()
                    && navigationHandles().stream()
                    .filter(handle -> handle.kind() == CodeNavigationHandle.Kind.CALL)
                    .anyMatch(handle -> expectedPath.equals(handle.path())
                            && sameNavigationSymbol(handle.symbol(), expectedSymbol));
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
                    .anyMatch(symbol -> expected.equals(symbol.chunkId()))
                    || relations.stream().anyMatch(relation ->
                    expected.equals(relation.fromChunkId()) || expected.equals(relation.toChunkId()));
        }

        /**
         * Detects the same proven source span requested through two typed read forms. The equivalence is
         * deliberately fail-closed: only a symbol that resolves to one observed active chunk is treated
         * as the same read, so overloads and same-named symbols in different files remain independently
         * retrievable.
         */
        boolean hasExecutedEquivalentRead(
                RagPipelineService.CodeSearchOperation operation,
                Set<String> executedOperationKeys
        ) {
            if (operation == null || executedOperationKeys == null || executedOperationKeys.isEmpty()) {
                return false;
            }
            if ("read_symbol".equals(operation.type())) {
                Set<UUID> chunks = observedChunksForSymbol(operation.path(), operation.symbol());
                return chunks.size() == 1 && executedOperationKeys.contains(
                        "read_chunk|" + chunks.iterator().next());
            }
            if (!"read_chunk".equals(operation.type())) return false;
            UUID requestedChunk;
            try {
                requestedChunk = UUID.fromString(safe(operation.chunkId()));
            } catch (IllegalArgumentException ignored) {
                return false;
            }
            for (String key : executedOperationKeys) {
                String[] parts = safe(key).split("\\|", -1);
                if (parts.length != 3 || !"read_symbol".equals(parts[0])) continue;
                Set<UUID> chunks = observedChunksForSymbol(parts[1], parts[2]);
                if (chunks.size() == 1 && chunks.contains(requestedChunk)) return true;
            }
            return false;
        }

        private Set<UUID> observedChunksForSymbol(String path, String symbol) {
            String expectedPath = safe(path);
            String expectedSymbol = safe(symbol);
            if (expectedSymbol.isBlank()) return Set.of();
            LinkedHashSet<UUID> chunks = new LinkedHashSet<>();
            evidence.values().stream()
                    .filter(entry -> entry.chunkId() != null)
                    .filter(entry -> expectedPath.isBlank() || expectedPath.equals(entry.path()))
                    .filter(entry -> sameNavigationSymbol(expectedSymbol, entry.symbol()))
                    .map(EvidenceEntry::chunkId)
                    .forEach(chunks::add);
            symbolInventories.values().stream()
                    .filter(inventory -> expectedPath.isBlank() || expectedPath.equals(inventory.path()))
                    .flatMap(inventory -> inventory.symbols().stream())
                    .filter(outline -> outline.chunkId() != null)
                    .filter(outline -> sameNavigationSymbol(expectedSymbol, outline.name())
                            || sameNavigationSymbol(expectedSymbol, outline.qualifiedName()))
                    .map(CodeSymbolOutline::chunkId)
                    .forEach(chunks::add);
            for (RelationEvidence relation : relations) {
                if (relation.fromChunkId() != null
                        && relationEndpointMatches(
                        relation.fromPath(), relation.from(), expectedPath, expectedSymbol)) {
                    chunks.add(relation.fromChunkId());
                }
                if (relation.toChunkId() != null
                        && relationEndpointMatches(
                        relation.toPath(), relation.to(), expectedPath, expectedSymbol)) {
                    chunks.add(relation.toChunkId());
                }
            }
            return Set.copyOf(chunks);
        }

        java.util.Optional<UUID> uniqueObservedChunkForSymbol(String path, String symbol) {
            Set<UUID> chunks = observedChunksForSymbol(path, symbol);
            return chunks.size() == 1
                    ? java.util.Optional.of(chunks.iterator().next())
                    : java.util.Optional.empty();
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
            return relations.stream().anyMatch(relation -> evidenceId.equals(relation.evidenceId())
                    && (expected.equals(relation.fromPath()) || expected.equals(relation.toPath())));
        }

        Set<String> observedTraversalRelations(String chunkId, String requestedDirection) {
            UUID seed;
            try {
                seed = UUID.fromString(safe(chunkId));
            } catch (IllegalArgumentException ignored) {
                return Set.of();
            }
            String direction = safe(requestedDirection).toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("FORWARD", "REVERSE", "BOTH").contains(direction)) direction = "BOTH";
            String expectedDirection = direction;
            return relations.stream()
                    .filter(relation -> seed.equals(relation.fromChunkId()))
                    .filter(relation -> relationDirectionMatches(expectedDirection, relation.direction()))
                    .map(RelationEvidence::type)
                    .map(value -> safe(value).toUpperCase(java.util.Locale.ROOT))
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        private boolean relationDirectionMatches(String requested, String observed) {
            String safeObserved = safe(observed).toUpperCase(java.util.Locale.ROOT);
            return "BOTH".equals(requested) || "BOTH".equals(safeObserved)
                    || requested.equals(safeObserved);
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

            if ("read_symbol".equals(operation.type()) && !symbol.isBlank()) {
                navigationHandles().stream()
                        .filter(handle -> handle.kind() == CodeNavigationHandle.Kind.CALL)
                        .filter(handle -> path.isBlank() || path.equals(handle.path()))
                        .filter(handle -> sameNavigationSymbol(handle.symbol(), symbol))
                        .map(CodeNavigationHandle::sourceEvidenceId)
                        .filter(evidence::containsKey)
                        .forEach(candidates::add);
            }
            if (("read_chunk".equals(operation.type()) || "read_adjacent".equals(operation.type())
                    || "traverse_graph".equals(operation.type())) && !chunkId.isBlank()) {
                navigationHandles().stream()
                        .filter(handle -> handle.chunkId() != null
                                && chunkId.equals(handle.chunkId().toString()))
                        .map(CodeNavigationHandle::sourceEvidenceId)
                        .filter(evidence::containsKey)
                        .forEach(candidates::add);
            }
            for (RelationEvidence relation : relations) {
                boolean pathMatches = path.isBlank()
                        || path.equals(relation.fromPath()) || path.equals(relation.toPath());
                boolean symbolMatches = symbol.isBlank()
                        || sameNavigationSymbol(symbol, relation.from())
                        || sameNavigationSymbol(symbol, relation.to());
                boolean chunkMatches = chunkId.isBlank()
                        || relation.fromChunkId() != null && chunkId.equals(relation.fromChunkId().toString())
                        || relation.toChunkId() != null && chunkId.equals(relation.toChunkId().toString());
                if (pathMatches && symbolMatches && chunkMatches) candidates.add(relation.evidenceId());
            }

            FileSymbolInventory exactInventory = symbolInventories.get(path);
            if (exactInventory != null && !exactInventory.evidenceId().isBlank()) {
                boolean symbolMatches = symbol.isBlank() || exactInventory.symbols().stream().anyMatch(outline ->
                        sameNavigationSymbol(symbol, outline.name())
                                || sameNavigationSymbol(symbol, outline.qualifiedName()));
                boolean chunkMatches = chunkId.isBlank() || exactInventory.symbols().stream().anyMatch(outline ->
                        outline.chunkId() != null && chunkId.equals(outline.chunkId().toString()));
                if (symbolMatches && chunkMatches) candidates.add(exactInventory.evidenceId());
            }
            for (EvidenceEntry entry : evidence.values()) {
                boolean chunkMatches = !chunkId.isBlank() && entry.chunkId() != null
                        && chunkId.equals(entry.chunkId().toString());
                boolean symbolMatches = !symbol.isBlank() && path.equals(entry.path())
                        && sameNavigationSymbol(symbol, entry.symbol());
                boolean pathMatches = !path.isBlank() && symbol.isBlank() && chunkId.isBlank()
                        && path.equals(entry.path());
                if (chunkMatches || symbolMatches || pathMatches) candidates.add(entry.evidenceId());
            }
            for (FileSymbolInventory inventory : symbolInventories.values()) {
                if (!path.isBlank() && !path.equals(inventory.path())) continue;
                boolean matches = inventory.symbols().stream().anyMatch(outline ->
                        (!chunkId.isBlank() && outline.chunkId() != null && chunkId.equals(outline.chunkId().toString()))
                                || (!symbol.isBlank() && (sameNavigationSymbol(symbol, outline.name())
                                || sameNavigationSymbol(symbol, outline.qualifiedName()))));
                if (matches || (!path.isBlank() && symbol.isBlank() && chunkId.isBlank())) {
                    if (!inventory.evidenceId().isBlank()) candidates.add(inventory.evidenceId());
                }
            }
            return candidates.stream().sorted().toList();
        }

        List<CodeNavigationHandle> navigationHandles() {
            return codeIntelligenceIr.navigationHandles().stream()
                    .filter(handle -> evidence.containsKey(handle.sourceEvidenceId()))
                    .toList();
        }

        public String plannerContext() {
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
            if (revision > 0) {
                appendEvidence(output, "DIRECT_BODIES_AND_DEFINITIONS",
                        List.of("IMPLEMENTATION_BODY", "DEFINITION"), 4,
                        MAX_IMPLEMENTATION_EXCERPT_CHARS);
                appendRelationHandles(output);
                appendNavigationHandles(output);
                appendSymbolInventories(output);
            } else {
                appendRelationHandles(output);
                appendNavigationHandles(output);
                appendSymbolInventories(output);
                appendEvidence(output, "DIRECT_BODIES_AND_DEFINITIONS",
                        List.of("IMPLEMENTATION_BODY", "DEFINITION"), 6, 360);
            }
            appendEvidence(output, "PROJECT_CONTEXT", List.of("PROJECT_CONTEXT"), 4, 360);
            appendEvidence(output, "REFERENCES_AND_NAVIGATION",
                    List.of("LEXICAL_OCCURRENCE", "NAVIGATION_HINT"), 4, 360);
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

        private void appendNavigationHandles(StringBuilder output) {
            List<CodeNavigationHandle> handles = retainedNavigationHandles();
            if (handles.isEmpty() || !appendRecord(output,
                    "\n[CODE_INTELLIGENCE_NAVIGATION_HANDLES] navigationOnly=true\n")) return;
            for (CodeNavigationHandle handle : handles) {
                String record = "- handleId=" + handle.handleId()
                        + " kind=" + handle.kind()
                        + " callerPath=" + handle.path()
                        + " observedSymbol=" + handle.symbol()
                        + " canonicalSymbol=" + canonicalNavigationSymbol(handle.symbol())
                        + " chunkId=" + (handle.chunkId() == null ? "" : handle.chunkId())
                        + " lines=" + handle.lineStart() + '-' + handle.lineEnd()
                        + " sourceEvidenceId=" + handle.sourceEvidenceId() + "\n";
                if (!appendRecord(output, record)) return;
            }
        }

        /**
         * Retains coverage across each observed callable instead of taking the alphabetically first
         * symbols. Buckets are visited round-robin and each bucket is ordered head, tail, midpoint,
         * then successively finer midpoints. Long Java, C#, or other language bodies therefore keep
         * entry and terminal transitions without any framework or question-type vocabulary.
         */
        private List<CodeNavigationHandle> retainedNavigationHandles() {
            Map<String, List<CodeNavigationHandle>> bySource = new LinkedHashMap<>();
            navigationHandles().forEach(handle -> bySource.computeIfAbsent(
                    handle.sourceEvidenceId(), ignored -> new ArrayList<>()).add(handle));
            List<Map.Entry<String, List<CodeNavigationHandle>>> orderedSources = bySource.entrySet().stream()
                    .sorted(Comparator
                            .comparingInt((Map.Entry<String, List<CodeNavigationHandle>> entry) ->
                                    navigationOperationRank(entry.getKey())).reversed()
                            .thenComparing(Comparator.comparingLong(
                                    (Map.Entry<String, List<CodeNavigationHandle>> entry) ->
                                            navigationRevision(entry.getKey())).reversed())
                            .thenComparing(Comparator.comparingDouble(
                                    (Map.Entry<String, List<CodeNavigationHandle>> entry) ->
                                            navigationScore(entry.getKey())).reversed())
                            .thenComparing(Map.Entry::getKey))
                    .toList();
            List<List<CodeNavigationHandle>> buckets = orderedSources.stream()
                    .map(Map.Entry::getValue)
                    .map(this::coverageOrderedHandles)
                    .filter(values -> !values.isEmpty())
                    .toList();
            List<List<CodeNavigationHandle>> primaryBuckets = List.of();
            if (!orderedSources.isEmpty()
                    && navigationOperationRank(orderedSources.get(0).getKey()) >= 6) {
                long latestExactRevision = orderedSources.stream()
                        .filter(entry -> navigationOperationRank(entry.getKey()) >= 6)
                        .mapToLong(entry -> navigationRevision(entry.getKey()))
                        .max()
                        .orElse(-1L);
                primaryBuckets = orderedSources.stream()
                        .filter(entry -> navigationOperationRank(entry.getKey()) >= 6)
                        .filter(entry -> navigationRevision(entry.getKey()) == latestExactRevision)
                        .limit(2)
                        .map(Map.Entry::getValue)
                        .map(this::coverageOrderedHandles)
                        .filter(values -> !values.isEmpty())
                        .toList();
            }
            LinkedHashMap<String, CodeNavigationHandle> selected = new LinkedHashMap<>();
            addRoundRobin(primaryBuckets, MAX_PRIMARY_NAVIGATION_HANDLES, selected);
            addRoundRobin(buckets, MAX_NAVIGATION_HANDLES, selected);
            return selected.values().stream()
                    .sorted(Comparator.comparingInt(
                                    (CodeNavigationHandle handle) -> navigationOperationRank(handle)).reversed()
                            .thenComparing(Comparator.comparingLong(
                                    (CodeNavigationHandle handle) -> navigationRevision(handle)).reversed())
                            .thenComparing(CodeNavigationHandle::path)
                            .thenComparingInt(CodeNavigationHandle::lineStart)
                            .thenComparing(Comparator.comparingInt(
                                    (CodeNavigationHandle handle) -> navigationKindRank(handle.kind())).reversed())
                            .thenComparing(CodeNavigationHandle::symbol))
                    .toList();
        }

        private void addRoundRobin(
                List<List<CodeNavigationHandle>> buckets,
                int limit,
                Map<String, CodeNavigationHandle> selected
        ) {
            if (buckets == null || buckets.isEmpty() || selected.size() >= limit) return;
            int maxDepth = buckets.stream().mapToInt(List::size).max().orElse(0);
            for (int depth = 0; depth < maxDepth && selected.size() < limit; depth++) {
                for (List<CodeNavigationHandle> bucket : buckets) {
                    if (depth >= bucket.size()) continue;
                    CodeNavigationHandle handle = bucket.get(depth);
                    selected.putIfAbsent(handle.handleId(), handle);
                    if (selected.size() >= limit) return;
                }
            }
        }

        private List<CodeNavigationHandle> coverageOrderedHandles(List<CodeNavigationHandle> sourceHandles) {
            List<CodeNavigationHandle> ordered = sourceHandles == null ? List.of() : sourceHandles.stream()
                    .sorted(Comparator.comparingInt(CodeNavigationHandle::lineStart)
                            .thenComparingInt(CodeNavigationHandle::lineEnd)
                            .thenComparing(CodeNavigationHandle::symbol))
                    .toList();
            List<CodeNavigationHandle> output = new ArrayList<>();
            for (CodeNavigationHandle.Kind kind : List.of(
                    CodeNavigationHandle.Kind.CALL,
                    CodeNavigationHandle.Kind.DEFINITION,
                    CodeNavigationHandle.Kind.TYPE)) {
                List<CodeNavigationHandle> sameKind = ordered.stream()
                        .filter(handle -> handle.kind() == kind).toList();
                for (int index : coverageOrder(sameKind.size())) output.add(sameKind.get(index));
            }
            return List.copyOf(output);
        }

        private List<Integer> coverageOrder(int size) {
            if (size <= 0) return List.of();
            LinkedHashSet<Integer> order = new LinkedHashSet<>();
            order.add(0);
            if (size > 1) order.add(size - 1);
            List<int[]> intervals = new ArrayList<>();
            if (size > 2) intervals.add(new int[]{1, size - 2});
            for (int cursor = 0; cursor < intervals.size(); cursor++) {
                int[] interval = intervals.get(cursor);
                if (interval[0] > interval[1]) continue;
                int midpoint = interval[0] + (interval[1] - interval[0]) / 2;
                order.add(midpoint);
                if (interval[0] <= midpoint - 1) intervals.add(new int[]{interval[0], midpoint - 1});
                if (midpoint + 1 <= interval[1]) intervals.add(new int[]{midpoint + 1, interval[1]});
            }
            return List.copyOf(order);
        }

        private long navigationRevision(CodeNavigationHandle handle) {
            return navigationRevision(handle == null ? "" : handle.sourceEvidenceId());
        }

        private long navigationRevision(String evidenceId) {
            EvidenceEntry entry = evidence.get(evidenceId);
            return entry == null ? -1L : entry.discoveredRevision();
        }

        private int navigationOperationRank(CodeNavigationHandle handle) {
            return navigationOperationRank(handle == null ? "" : handle.sourceEvidenceId());
        }

        private int navigationOperationRank(String evidenceId) {
            return operationProofRank(evidence.get(evidenceId));
        }

        private double navigationScore(String evidenceId) {
            EvidenceEntry entry = evidence.get(evidenceId);
            return entry == null ? 0.0 : entry.score();
        }

        private void appendRelationHandles(StringBuilder output) {
            if (relations.isEmpty() || !appendRecord(output,
                    "\n[INDEXED_GRAPH_RELATION_HANDLES] navigationOnly=true\n")) return;
            for (RelationEvidence relation : retainedPromptRelations()) {
                String record = "- evidenceId=" + relation.evidenceId()
                        + " seedPath=" + relation.fromPath()
                        + " seedSymbol=" + relation.from()
                        + " seedChunkId=" + (relation.fromChunkId() == null ? "" : relation.fromChunkId())
                        + " relation=" + relation.type()
                        + " direction=" + relation.direction()
                        + " neighborPath=" + relation.toPath()
                        + " neighborSymbol=" + relation.to()
                        + " neighborChunkId=" + (relation.toChunkId() == null ? "" : relation.toChunkId())
                        + " navigationOnly=" + relation.navigationOnly() + "\n";
                if (!appendRecord(output, record)) return;
            }
        }

        /**
         * Keeps relation handles representative of independent source observations. Exact reads
         * receive a bounded reservation, then every source is visited round-robin. This prevents a
         * relation-rich search hit from hiding other retrieved anchors without inspecting project,
         * framework, path, or question-specific names.
         */
        private List<RelationEvidence> retainedPromptRelations() {
            Map<String, List<RelationEvidence>> bySource = new LinkedHashMap<>();
            relations.forEach(relation -> bySource.computeIfAbsent(
                    relation.sourceEvidenceId(), ignored -> new ArrayList<>()).add(relation));
            List<Map.Entry<String, List<RelationEvidence>>> orderedSources = bySource.entrySet().stream()
                    .sorted(Comparator
                            .comparingInt((Map.Entry<String, List<RelationEvidence>> entry) ->
                                    relationOperationRank(entry.getKey())).reversed()
                            .thenComparing(Comparator.comparingLong(
                                    (Map.Entry<String, List<RelationEvidence>> entry) ->
                                            relationRevision(entry.getKey())).reversed())
                            .thenComparing(Comparator.comparingDouble(
                                    (Map.Entry<String, List<RelationEvidence>> entry) ->
                                            relationSourceScore(entry.getValue())).reversed())
                            .thenComparing(Map.Entry::getKey))
                    .toList();
            List<List<RelationEvidence>> buckets = orderedSources.stream()
                    .map(Map.Entry::getValue)
                    .filter(values -> !values.isEmpty())
                    .toList();
            List<List<RelationEvidence>> primaryBuckets = List.of();
            if (!orderedSources.isEmpty()
                    && relationOperationRank(orderedSources.get(0).getKey()) >= 6) {
                long latestExactRevision = orderedSources.stream()
                        .filter(entry -> relationOperationRank(entry.getKey()) >= 6)
                        .mapToLong(entry -> relationRevision(entry.getKey()))
                        .max()
                        .orElse(-1L);
                primaryBuckets = orderedSources.stream()
                        .filter(entry -> relationOperationRank(entry.getKey()) >= 6)
                        .filter(entry -> relationRevision(entry.getKey()) == latestExactRevision)
                        .limit(2)
                        .map(Map.Entry::getValue)
                        .filter(values -> !values.isEmpty())
                        .toList();
            }
            LinkedHashMap<String, RelationEvidence> selected = new LinkedHashMap<>();
            addRelationRoundRobin(primaryBuckets, MAX_PRIMARY_RELATION_HANDLES, selected);
            addRelationRoundRobin(buckets, MAX_PROMPT_RELATION_HANDLES, selected);
            return List.copyOf(selected.values());
        }

        private void addRelationRoundRobin(
                List<List<RelationEvidence>> buckets,
                int limit,
                Map<String, RelationEvidence> selected
        ) {
            if (buckets == null || buckets.isEmpty() || selected.size() >= limit) return;
            int maxDepth = buckets.stream().mapToInt(List::size).max().orElse(0);
            for (int depth = 0; depth < maxDepth && selected.size() < limit; depth++) {
                for (List<RelationEvidence> bucket : buckets) {
                    if (depth >= bucket.size()) continue;
                    RelationEvidence relation = bucket.get(depth);
                    selected.putIfAbsent(relation.evidenceId(), relation);
                    if (selected.size() >= limit) return;
                }
            }
        }

        private int relationOperationRank(String evidenceId) {
            return operationProofRank(evidence.get(evidenceId));
        }

        private long relationRevision(String evidenceId) {
            EvidenceEntry entry = evidence.get(evidenceId);
            return entry == null ? -1L : entry.discoveredRevision();
        }

        private double relationSourceScore(List<RelationEvidence> values) {
            return values == null ? 0.0 : values.stream()
                    .mapToDouble(RelationEvidence::sourceScore)
                    .max()
                    .orElse(0.0);
        }

        private void appendSymbolInventories(StringBuilder output) {
            output.append("\n[FILE_SYMBOL_INVENTORIES]\n");
            List<FileSymbolInventory> inventories = symbolInventories.values().stream()
                    .limit(MAX_INVENTORY_FILES).toList();
            int perFileLimit = inventories.size() <= 1
                    ? MAX_SYMBOLS_PER_FILE
                    : Math.max(8, Math.min(64, 128 / inventories.size()));
            for (FileSymbolInventory inventory : inventories) {
                int remaining = Math.max(0, MAX_PROMPT_CHARS - output.length() - 280);
                List<String> symbolLines = new ArrayList<>();
                int used = 0;
                for (CodeSymbolOutline symbol : inventory.symbols()) {
                    if (symbolLines.size() >= perFileLimit) break;
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

        private void appendEvidence(
                StringBuilder output,
                String section,
                List<String> kinds,
                int limit,
                int excerptLimit
        ) {
            if (!appendRecord(output, "\n[" + section + "]\n")) return;
            List<EvidenceEntry> ranked = evidence.values().stream()
                    .filter(entry -> kinds.contains(entry.kind()))
                    .sorted(evidencePriority())
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
                String record = "- evidenceId=" + entry.evidenceId() + " kind=" + entry.kind()
                        + " authority=" + entry.authority() + " path=" + entry.path()
                        + " symbol=" + entry.symbol() + " lines=" + entry.lineStart() + '-' + entry.lineEnd()
                        + " chunkId=" + (entry.chunkId() == null ? "" : entry.chunkId())
                        + " origin=" + entry.origin() + " discoveredRevision=" + entry.discoveredRevision()
                        + "\n  excerpt=" + truncate(entry.excerpt(), Math.max(1, excerptLimit)) + "\n";
                if (!appendRecord(output, record)) return;
            }
        }

        private static int navigationKindRank(CodeNavigationHandle.Kind kind) {
            if (kind == null) return 0;
            return switch (kind) {
                case CALL -> 3;
                case DEFINITION -> 2;
                case TYPE -> 1;
            };
        }

        private static boolean sameNavigationSymbol(String left, String right) {
            String canonicalLeft = canonicalNavigationSymbol(left);
            String canonicalRight = canonicalNavigationSymbol(right);
            return !canonicalLeft.isBlank() && canonicalLeft.equalsIgnoreCase(canonicalRight);
        }

        private static String canonicalNavigationSymbol(String value) {
            String symbol = safe(value);
            int parameters = symbol.indexOf('(');
            if (parameters >= 0) symbol = symbol.substring(0, parameters);
            symbol = symbol.replace("::", ".").replace('#', '.');
            int separator = symbol.lastIndexOf('.');
            if (separator >= 0 && separator + 1 < symbol.length()) {
                symbol = symbol.substring(separator + 1);
            }
            int generic = symbol.indexOf('<');
            return (generic > 0 ? symbol.substring(0, generic) : symbol).trim();
        }
    }
}
