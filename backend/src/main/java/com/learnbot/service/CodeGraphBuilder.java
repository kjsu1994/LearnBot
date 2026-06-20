package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeGraphBuilder {
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b");
    private static final int MAX_REFERENCES_PER_CHUNK = 24;

    private final LearnBotProperties properties;
    private final JavaSemanticGraphAnalyzer javaAnalyzer;
    private final RoslynSemanticGraphAnalyzer roslynAnalyzer;
    private final CodeGraphLlmEnricher llmEnricher;

    public CodeGraphBuilder(LearnBotProperties properties) {
        this(properties, null, null, null);
    }

    @Autowired
    public CodeGraphBuilder(LearnBotProperties properties, JavaSemanticGraphAnalyzer javaAnalyzer,
                            RoslynSemanticGraphAnalyzer roslynAnalyzer, CodeGraphLlmEnricher llmEnricher) {
        this.properties = properties;
        this.javaAnalyzer = javaAnalyzer;
        this.roslynAnalyzer = roslynAnalyzer;
        this.llmEnricher = llmEnricher;
    }

    public boolean enabled() {
        return properties.getCode().getGraph().isEnabled();
    }

    public CodeGraph build(List<CodeSearchResult> chunks) {
        return build(null, chunks);
    }

    public CodeGraph build(Path repositoryRoot, List<CodeSearchResult> chunks) {
        if (!enabled() || chunks == null || chunks.isEmpty()) {
            return new CodeGraph(List.of(), List.of());
        }
        Map<String, CodeGraphNode> nodes = new LinkedHashMap<>();
        Map<String, CodeGraphEdge> edges = new LinkedHashMap<>();
        Map<String, List<CodeSearchResult>> symbols = symbolIndex(chunks);

        for (CodeSearchResult chunk : chunks.stream().sorted(Comparator.comparing(CodeSearchResult::filePath).thenComparingInt(CodeSearchResult::chunkIndex)).toList()) {
            String fileKey = fileKey(chunk.filePath());
            addNode(nodes, new CodeGraphNode(fileKey, "file", chunk.filePath(), chunk.filePath(), chunk.filePath(), chunk.chunkId(), Map.of()));
            addDirectoryNodes(nodes, edges, chunk);
            String symbolKey = addSymbolNode(nodes, chunk);
            if (symbolKey != null) {
                addEdge(edges, fileKey, symbolKey, "DEFINES", 1.0, chunk.chunkId(), Map.of("source", "chunk_metadata"));
                String classKey = classKey(chunk);
                if (classKey != null && !classKey.equals(symbolKey)) {
                    addEdge(edges, classKey, symbolKey, "CONTAINS", 0.95, chunk.chunkId(), Map.of("source", "chunk_metadata"));
                }
            }
            addXamlEdges(edges, chunk, symbols);
        }

        for (CodeSearchResult chunk : chunks) {
            String sourceKey = bestNodeKey(chunk);
            if (sourceKey == null) {
                continue;
            }
            int added = 0;
            for (String identifier : identifiers(chunk.content())) {
                if (matchesOwnSymbol(chunk, identifier)) {
                    continue;
                }
                List<CodeSearchResult> targets = symbols.get(normalize(identifier));
                if (targets == null || targets.isEmpty()) {
                    continue;
                }
                for (CodeSearchResult target : targets.stream().limit(3).toList()) {
                    String targetKey = bestNodeKey(target);
                    if (targetKey != null && !sourceKey.equals(targetKey)) {
                        addEdge(edges, sourceKey, targetKey, "REFERENCES", 0.55, chunk.chunkId(), Map.of(
                                "identifier", identifier,
                                "source", "deterministic_text_reference"
                        ));
                        if (++added >= MAX_REFERENCES_PER_CHUNK) {
                            break;
                        }
                    }
                }
                if (added >= MAX_REFERENCES_PER_CHUNK) {
                    break;
                }
            }
        }
        if (javaAnalyzer != null && repositoryRoot != null) {
            try {
                merge(nodes, edges, javaAnalyzer.analyze(repositoryRoot, chunks));
            } catch (RuntimeException ignored) {
                // The conservative chunk graph remains available when semantic analysis fails.
            }
        }
        if (roslynAnalyzer != null && repositoryRoot != null) {
            try {
                merge(nodes, edges, roslynAnalyzer.analyze(repositoryRoot, chunks));
            } catch (RuntimeException ignored) {
                // C# projects keep the conservative chunk graph when Roslyn is unavailable.
            }
        }
        CodeGraph graph = new CodeGraph(List.copyOf(nodes.values()), List.copyOf(edges.values()));
        if (llmEnricher != null) {
            try {
                return llmEnricher.enrich(graph, chunks);
            } catch (RuntimeException ignored) {
                // Deterministic graph creation must not depend on LLM availability.
            }
        }
        return graph;
    }

    private void merge(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, CodeGraph graph) {
        if (graph == null) {
            return;
        }
        graph.nodes().forEach(node -> nodes.putIfAbsent(node.key(), node));
        graph.edges().forEach(edge -> edges.putIfAbsent(
                edge.sourceKey() + "|" + edge.type() + "|" + edge.targetKey(), edge
        ));
    }

    private Map<String, List<CodeSearchResult>> symbolIndex(List<CodeSearchResult> chunks) {
        Map<String, List<CodeSearchResult>> index = new LinkedHashMap<>();
        for (CodeSearchResult chunk : chunks) {
            for (String symbol : List.of(safe(chunk.symbolName()), safe(chunk.className()), safe(chunk.methodName()), safe(chunk.controlName()), safe(chunk.eventName()))) {
                if (!symbol.isBlank()) {
                    index.computeIfAbsent(normalize(symbol), ignored -> new ArrayList<>()).add(chunk);
                }
            }
        }
        return index;
    }

    private void addDirectoryNodes(Map<String, CodeGraphNode> nodes, Map<String, CodeGraphEdge> edges, CodeSearchResult chunk) {
        String path = safe(chunk.filePath());
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return;
        }
        String directory = path.substring(0, slash);
        String directoryKey = "directory:" + directory;
        addNode(nodes, new CodeGraphNode(directoryKey, "directory", directory, directory, directory, null, Map.of()));
        addEdge(edges, directoryKey, fileKey(path), "CONTAINS", 1.0, chunk.chunkId(), Map.of("source", "file_path"));
    }

    private String addSymbolNode(Map<String, CodeGraphNode> nodes, CodeSearchResult chunk) {
        String type = symbolType(chunk);
        String name = firstNonBlank(chunk.methodName(), chunk.className(), chunk.controlName(), chunk.eventName(), chunk.symbolName());
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = nodeKey(type, chunk.filePath(), name);
        addNode(nodes, new CodeGraphNode(
                key,
                type,
                name,
                qualifiedName(chunk, name),
                chunk.filePath(),
                chunk.chunkId(),
                Map.of("chunkType", safe(chunk.chunkType()))
        ));
        return key;
    }

    private void addXamlEdges(Map<String, CodeGraphEdge> edges, CodeSearchResult chunk, Map<String, List<CodeSearchResult>> symbols) {
        if (chunk.eventName() != null && "xaml_event".equals(chunk.chunkType())) {
            List<CodeSearchResult> handlers = symbols.get(normalize(chunk.eventName()));
            if (handlers != null) {
                for (CodeSearchResult handler : handlers) {
                    String handlerKey = bestNodeKey(handler);
                    String eventKey = bestNodeKey(chunk);
                    if (handlerKey != null && eventKey != null && !handlerKey.equals(eventKey)) {
                        addEdge(edges, eventKey, handlerKey, "HANDLES_EVENT", 0.98, chunk.chunkId(), Map.of("source", "xaml_event"));
                    }
                }
            }
        }
        Object binding = chunk.metadata() == null ? null : chunk.metadata().get("binding");
        if (binding != null && bestNodeKey(chunk) != null) {
            List<CodeSearchResult> targets = symbols.get(normalize(String.valueOf(binding)));
            if (targets != null) {
                for (CodeSearchResult target : targets.stream().limit(3).toList()) {
                    String targetKey = bestNodeKey(target);
                    if (targetKey != null) {
                        addEdge(edges, bestNodeKey(chunk), targetKey, "BINDS_TO", 0.82, chunk.chunkId(), Map.of("binding", String.valueOf(binding)));
                    }
                }
            }
        }
    }

    private Set<String> identifiers(String content) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(content == null ? "" : content);
        while (matcher.find()) {
            String value = matcher.group();
            if (!isCommon(value)) {
                values.add(value);
            }
            if (values.size() >= 80) {
                break;
            }
        }
        return values;
    }

    private boolean matchesOwnSymbol(CodeSearchResult chunk, String identifier) {
        String normalized = normalize(identifier);
        return normalized.equals(normalize(chunk.methodName()))
                || normalized.equals(normalize(chunk.className()))
                || normalized.equals(normalize(chunk.symbolName()))
                || normalized.equals(normalize(chunk.controlName()))
                || normalized.equals(normalize(chunk.eventName()));
    }

    private String bestNodeKey(CodeSearchResult chunk) {
        String name = firstNonBlank(chunk.methodName(), chunk.className(), chunk.controlName(), chunk.eventName(), chunk.symbolName());
        if (name != null && !name.isBlank()) {
            return nodeKey(symbolType(chunk), chunk.filePath(), name);
        }
        return fileKey(chunk.filePath());
    }

    private String classKey(CodeSearchResult chunk) {
        if (chunk.className() == null || chunk.className().isBlank()) {
            return null;
        }
        return nodeKey("class", chunk.filePath(), chunk.className());
    }

    private String symbolType(CodeSearchResult chunk) {
        if (chunk.controlName() != null && !chunk.controlName().isBlank()) {
            return "xaml_control";
        }
        if (chunk.eventName() != null && !chunk.eventName().isBlank()) {
            return "event_handler";
        }
        if (chunk.methodName() != null && !chunk.methodName().isBlank()) {
            return "method";
        }
        if (chunk.className() != null && !chunk.className().isBlank()) {
            return "class";
        }
        return "symbol";
    }

    private String qualifiedName(CodeSearchResult chunk, String name) {
        if (chunk.namespaceName() != null && !chunk.namespaceName().isBlank()) {
            return chunk.namespaceName() + "." + name;
        }
        if (chunk.className() != null && !chunk.className().isBlank() && !chunk.className().equals(name)) {
            return chunk.className() + "." + name;
        }
        return name;
    }

    private String fileKey(String filePath) {
        return "file:" + safe(filePath);
    }

    private String nodeKey(String type, String filePath, String name) {
        return type + ":" + safe(filePath) + ":" + safe(name);
    }

    private void addNode(Map<String, CodeGraphNode> nodes, CodeGraphNode node) {
        nodes.putIfAbsent(node.key(), node);
    }

    private void addEdge(Map<String, CodeGraphEdge> edges, String sourceKey, String targetKey, String type, double confidence, UUID evidenceChunkId, Map<String, Object> metadata) {
        if (sourceKey == null || targetKey == null || sourceKey.equals(targetKey)) {
            return;
        }
        edges.putIfAbsent(sourceKey + "|" + type + "|" + targetKey, new CodeGraphEdge(sourceKey, targetKey, type, confidence, evidenceChunkId, metadata));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isCommon(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.length() < 3 || Set.of(
                "public", "private", "protected", "class", "return", "string", "integer", "boolean",
                "static", "final", "void", "null", "true", "false", "this", "new", "var", "let", "const"
        ).contains(lower);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
