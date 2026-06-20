package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final LearnBotProperties properties;
    private final ObjectMapper objectMapper;

    public RoslynSemanticGraphAnalyzer(LearnBotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CodeGraph analyze(Path repositoryRoot, List<CodeSearchResult> chunks) {
        return analyzeWithDiagnostics(repositoryRoot, chunks).graph();
    }

    public CodeGraphAnalysisResult analyzeWithDiagnostics(Path repositoryRoot, List<CodeSearchResult> chunks) {
        long started = System.nanoTime();
        String analyzerPath = properties.getCode().getGraph().getRoslynAnalyzerPath();
        int attemptedFiles = countFiles(repositoryRoot, ".cs");
        String mode = determineMode(repositoryRoot);
        if (attemptedFiles == 0) {
            return new CodeGraphAnalysisResult(empty(), CodeAnalysisDiagnostic.skipped(
                    "CSHARP_ROSLYN", "Roslyn", mode, "No C# source files found."
            ));
        }
        if (repositoryRoot == null || analyzerPath == null || analyzerPath.isBlank()
                || !Files.isDirectory(repositoryRoot) || !Files.isRegularFile(Path.of(analyzerPath))) {
            return failed(mode, attemptedFiles, started, "Roslyn analyzer is unavailable.");
        }
        Process process = null;
        try {
            process = new ProcessBuilder("dotnet", analyzerPath, repositoryRoot.toAbsolutePath().normalize().toString(), mode).start();
            Process running = process;
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(running.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(running.getErrorStream()));
            if (!process.waitFor(Math.max(1, properties.getCode().getGraph().getRoslynTimeoutSeconds()), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return failed(mode, attemptedFiles, started, "Roslyn analyzer timed out.");
            }
            String json = stdout.join();
            stderr.join();
            if (process.exitValue() != 0 || json.isBlank()) {
                return failed(mode, attemptedFiles, started, "Roslyn analyzer returned no usable output.");
            }
            AnalyzerOutput output = objectMapper.readValue(json, AnalyzerOutput.class);
            CodeGraph graph = map(output, chunks);
            int analyzedFiles = output.analyzedFiles() > 0 ? output.analyzedFiles() : attemptedFiles;
            int failedFiles = Math.max(output.failedFiles(), attemptedFiles - analyzedFiles);
            return new CodeGraphAnalysisResult(graph, new CodeAnalysisDiagnostic(
                    "CSHARP_ROSLYN", "Roslyn", failedFiles == 0 ? "SUCCESS" : "PARTIAL",
                    output.mode() == null ? mode : output.mode(), attemptedFiles, analyzedFiles, failedFiles,
                    graph.edges().size(), 0, graph.nodes().size(), graph.edges().size(), elapsedMillis(started),
                    failedFiles == 0 ? "Roslyn semantic analysis completed." : "Some C# files or projects used fallback analysis.",
                    Map.of("projects", output.projectCount())
            ));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return failed(mode, attemptedFiles, started, "Roslyn analyzer was interrupted.");
        } catch (RuntimeException | IOException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            return failed(mode, attemptedFiles, started, "Roslyn analyzer failed: " + ex.getClass().getSimpleName());
        }
    }

    private CodeGraphAnalysisResult failed(String mode, int attemptedFiles, long started, String message) {
        return new CodeGraphAnalysisResult(empty(), new CodeAnalysisDiagnostic(
                "CSHARP_ROSLYN", "Roslyn", "FAILED", mode, attemptedFiles, 0, attemptedFiles,
                0, 0, 0, 0, elapsedMillis(started), message, Map.of()
        ));
    }

    private String determineMode(Path root) {
        String requested = properties.getCode().getGraph().getRoslynMode();
        if (requested != null && !requested.isBlank() && !"AUTO".equalsIgnoreCase(requested)) {
            return requested.trim().toUpperCase(Locale.ROOT);
        }
        if (hasFile(root, ".sln")) return "SOLUTION";
        if (hasFile(root, ".csproj")) return "PROJECT";
        return "SIMPLE";
    }

    private boolean hasFile(Path root, String suffix) { return countFiles(root, suffix) > 0; }

    private int countFiles(Path root, String suffix) {
        if (root == null || !Files.isDirectory(root)) return 0;
        try (var paths = Files.walk(root)) {
            return (int) paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(suffix)).count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
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

    private record AnalyzerOutput(List<AnalyzerNode> nodes, List<AnalyzerEdge> edges, String mode,
                                  int projectCount, int analyzedFiles, int failedFiles) {}
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
