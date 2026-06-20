package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class RoslynSemanticGraphAnalyzer {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final LearnBotProperties properties;
    private final ObjectMapper objectMapper;

    public RoslynSemanticGraphAnalyzer(LearnBotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CodeGraph analyze(Path repositoryRoot, List<CodeSearchResult> chunks) {
        String analyzerPath = properties.getCode().getGraph().getRoslynAnalyzerPath();
        if (repositoryRoot == null || analyzerPath == null || analyzerPath.isBlank()
                || !Files.isDirectory(repositoryRoot) || !Files.isRegularFile(Path.of(analyzerPath))) {
            return empty();
        }
        Process process = null;
        try {
            process = new ProcessBuilder("dotnet", analyzerPath, repositoryRoot.toAbsolutePath().normalize().toString()).start();
            Process running = process;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(running.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(running.getErrorStream()));
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return empty();
            }
            String json = stdout.join();
            stderr.join();
            if (process.exitValue() != 0 || json.isBlank()) {
                return empty();
            }
            AnalyzerOutput output = objectMapper.readValue(json, AnalyzerOutput.class);
            return map(output, chunks);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return empty();
        } catch (RuntimeException | IOException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            return empty();
        }
    }

    private CodeGraph map(AnalyzerOutput output, List<CodeSearchResult> chunks) {
        ChunkLookup lookup = new ChunkLookup(chunks);
        Map<String, CodeGraphNode> nodes = new LinkedHashMap<>();
        Map<String, CodeGraphEdge> edges = new LinkedHashMap<>();
        if (output != null && output.nodes() != null) {
            for (AnalyzerNode node : output.nodes()) {
                UUID chunkId = lookup.find(node.filePath(), node.line());
                nodes.putIfAbsent(node.key(), new CodeGraphNode(
                        node.key(), node.type(), node.name(), node.qualifiedName(), node.filePath(), chunkId,
                        Map.of("language", "csharp", "source", "roslyn_semantic_model")
                ));
            }
        }
        if (output != null && output.edges() != null) {
            for (AnalyzerEdge edge : output.edges()) {
                UUID chunkId = lookup.find(edge.filePath(), edge.line());
                edges.putIfAbsent(edge.sourceKey() + "|" + edge.type() + "|" + edge.targetKey(), new CodeGraphEdge(
                        edge.sourceKey(), edge.targetKey(), edge.type(), edge.confidence(), chunkId,
                        Map.of("source", edge.source() == null ? "roslyn_semantic_model" : edge.source())
                ));
            }
        }
        return new CodeGraph(List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private String read(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private CodeGraph empty() {
        return new CodeGraph(List.of(), List.of());
    }

    private record AnalyzerOutput(List<AnalyzerNode> nodes, List<AnalyzerEdge> edges) {}
    private record AnalyzerNode(String key, String type, String name, String qualifiedName, String filePath, int line) {}
    private record AnalyzerEdge(String sourceKey, String targetKey, String type, double confidence,
                                String filePath, int line, String source) {}

    private static final class ChunkLookup {
        private final Map<String, List<CodeSearchResult>> byPath = new HashMap<>();

        private ChunkLookup(List<CodeSearchResult> chunks) {
            if (chunks != null) {
                chunks.forEach(chunk -> byPath.computeIfAbsent(normalize(chunk.filePath()), ignored -> new ArrayList<>()).add(chunk));
            }
        }

        private UUID find(String path, int line) {
            List<CodeSearchResult> candidates = byPath.getOrDefault(normalize(path), List.of());
            return candidates.stream()
                    .filter(chunk -> line <= 0 || (chunk.lineStart() <= line && chunk.lineEnd() >= line))
                    .min(Comparator.comparingInt(chunk -> Math.abs(chunk.lineStart() - line)))
                    .map(CodeSearchResult::chunkId)
                    .orElse(null);
        }

        private static String normalize(String path) {
            return path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        }
    }
}
