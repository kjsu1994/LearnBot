package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class CodeProjectContextBuilder {
    public static final String CONTEXT_FILE_PATH = "__learnbot__/project-context.md";
    private static final int STRUCTURE_VERSION = 1;
    private static final int MAX_CONTEXT_CHARS = 9000;

    private final LearnBotProperties properties;
    private final OllamaClient ollamaClient;

    public CodeProjectContextBuilder(LearnBotProperties properties, OllamaClient ollamaClient) {
        this.properties = properties;
        this.ollamaClient = ollamaClient;
    }

    public boolean enabled() {
        return properties.getCode().getContext().isEnabled();
    }

    public List<ParsedCodeChunk> build(CodeRepositoryRecord repository, List<IndexedFileContext> files) {
        if (!enabled() || files == null || files.isEmpty()) {
            return List.of();
        }
        ProjectFacts facts = facts(files);
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        addChunk(chunks, "project_structure", "project-structure", structureContent(repository, facts), Map.of(
                "kind", "project_context",
                "summaryLevel", "repository",
                "generatedBy", "deterministic"
        ));
        HybridText repositorySummary = repositorySummary(repository, facts);
        addChunk(chunks, "repository_summary", "repository-summary", repositorySummary.content(), Map.of(
                "kind", "project_context",
                "summaryLevel", "repository",
                "generatedBy", repositorySummary.generatedBy(),
                "llmAttempted", llmEnabled(),
                "llmSucceeded", repositorySummary.llmSucceeded()
        ));

        int llmDirectoryBudget = Math.max(0, properties.getCode().getContext().getMaxLlmDirectorySummaries());
        for (DirectoryFacts directory : facts.directories().stream().limit(24).toList()) {
            boolean tryLlm = llmEnabled() && llmDirectoryBudget-- > 0;
            HybridText summary = directorySummary(repository, directory, tryLlm);
            addChunk(chunks, "directory_summary", directory.path(), summary.content(), Map.of(
                    "kind", "project_context",
                    "summaryLevel", "directory",
                    "directory", directory.path(),
                    "generatedBy", summary.generatedBy(),
                    "llmAttempted", tryLlm,
                    "llmSucceeded", summary.llmSucceeded()
            ));
        }

        files.stream()
                .sorted(Comparator.comparingInt(this::fileImportance).reversed().thenComparing(IndexedFileContext::path))
                .limit(Math.max(1, properties.getCode().getContext().getMaxFileSummaries()))
                .forEach(file -> addChunk(chunks, "file_summary", file.path(), fileSummary(file), Map.of(
                        "kind", "project_context",
                        "summaryLevel", "file",
                        "sourceFile", file.path(),
                        "language", file.language(),
                        "generatedBy", "deterministic"
                )));
        return chunks;
    }

    private ProjectFacts facts(List<IndexedFileContext> files) {
        Map<String, Long> languages = files.stream()
                .collect(Collectors.groupingBy(IndexedFileContext::language, TreeMap::new, Collectors.counting()));
        Set<String> frameworks = new LinkedHashSet<>();
        Set<String> entrypoints = new LinkedHashSet<>();
        Map<String, DirectoryAccumulator> directoryMap = new TreeMap<>();
        for (IndexedFileContext file : files) {
            detectFrameworks(file, frameworks);
            if (isEntrypoint(file)) {
                entrypoints.add(file.path());
            }
            String directory = topDirectory(file.path());
            DirectoryAccumulator accumulator = directoryMap.computeIfAbsent(directory, DirectoryAccumulator::new);
            accumulator.add(file);
        }
        List<DirectoryFacts> directories = directoryMap.values().stream()
                .map(DirectoryAccumulator::toFacts)
                .sorted(Comparator.comparingInt(DirectoryFacts::score).reversed().thenComparing(DirectoryFacts::path))
                .toList();
        return new ProjectFacts(files, languages, frameworks, entrypoints, directories, tree(files));
    }

    private String structureContent(CodeRepositoryRecord repository, ProjectFacts facts) {
        return """
                Project structure context
                Repository: %s
                Branch: %s
                Indexed files: %d
                Languages: %s
                Framework and build signals: %s
                Entrypoints: %s

                Directory tree:
                %s

                Layer hints:
                %s
                """.formatted(
                repository.name(),
                repository.branch(),
                facts.files().size(),
                joinMap(facts.languages()),
                joinOrDash(facts.frameworks()),
                joinOrDash(facts.entrypoints()),
                facts.tree(),
                layerHints(facts.files())
        ).strip();
    }

    private HybridText repositorySummary(CodeRepositoryRecord repository, ProjectFacts facts) {
        String deterministic = """
                Repository summary
                %s is indexed as a source-code project with %d files.
                Main language signals: %s.
                Main framework/build signals: %s.
                Important entrypoints: %s.
                Main directories: %s.
                Use this chunk for architecture, project overview, module map, and high-level responsibility questions.
                """.formatted(
                repository.name(),
                facts.files().size(),
                joinMap(facts.languages()),
                joinOrDash(facts.frameworks()),
                joinOrDash(facts.entrypoints()),
                facts.directories().stream().limit(8).map(DirectoryFacts::path).collect(Collectors.joining(", "))
        ).strip();
        if (!llmEnabled()) {
            return new HybridText(deterministic, "deterministic", false);
        }
        return llmSummary("Summarize this repository architecture for code retrieval. Keep concrete file and directory names.",
                deterministic + "\n\n" + facts.directories().stream()
                        .limit(12)
                        .map(DirectoryFacts::shortText)
                        .collect(Collectors.joining("\n")),
                deterministic);
    }

    private HybridText directorySummary(CodeRepositoryRecord repository, DirectoryFacts directory, boolean tryLlm) {
        String deterministic = """
                Directory summary
                Repository: %s
                Directory: %s
                Files: %d
                Languages: %s
                Representative files: %s
                Representative symbols: %s
                Inferred responsibility: %s
                """.formatted(
                repository.name(),
                directory.path(),
                directory.fileCount(),
                joinMap(directory.languages()),
                joinOrDash(directory.representativeFiles()),
                joinOrDash(directory.symbols()),
                directory.responsibility()
        ).strip();
        if (!tryLlm) {
            return new HybridText(deterministic, "deterministic", false);
        }
        return llmSummary("Summarize this directory for code retrieval. Mention responsibility, important files, and symbols.",
                deterministic,
                deterministic);
    }

    private String fileSummary(IndexedFileContext file) {
        List<String> symbols = symbols(file.chunks(), 12);
        return """
                File summary
                File: %s
                Language: %s
                Lines: %d
                Chunk types: %s
                Symbols: %s
                Inferred role: %s
                Search keywords: %s
                """.formatted(
                file.path(),
                file.language(),
                lineCount(file.content()),
                chunkTypes(file.chunks()),
                joinOrDash(symbols),
                role(file.path()),
                roleKeywords(file.path())
        ).strip();
    }

    private HybridText llmSummary(String instruction, String context, String fallback) {
        try {
            String response = ollamaClient.chat(
                    """
                            You create compact source-code retrieval summaries.
                            Use only the provided facts. Do not invent files or behavior.
                            Return plain text with concrete paths, symbols, and responsibilities.
                            """,
                    instruction + "\n\nFacts:\n" + trim(maskSecrets(context), MAX_CONTEXT_CHARS),
                    OllamaClient.ChatRole.AUXILIARY
            );
            String clean = response == null ? "" : response.strip();
            if (clean.length() < 20) {
                return new HybridText(fallback, "deterministic", false);
            }
            return new HybridText(clean, "llm_auxiliary", true);
        } catch (RuntimeException ex) {
            return new HybridText(fallback, "deterministic", false);
        }
    }

    private void addChunk(List<ParsedCodeChunk> chunks, String type, String symbol, String content, Map<String, Object> metadata) {
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        values.put("language", "markdown");
        values.put("parser", "project_context");
        values.put("strategy", "project_context");
        values.put("structureVersion", STRUCTURE_VERSION);
        String body = "File: " + CONTEXT_FILE_PATH + "\n"
                + "Lines: 1-" + Math.max(1, lineCount(content)) + "\n"
                + trim(maskSecrets(content), MAX_CONTEXT_CHARS);
        chunks.add(new ParsedCodeChunk(
                chunks.size(),
                type,
                symbol,
                null,
                null,
                null,
                null,
                null,
                1,
                Math.max(1, lineCount(content)),
                body,
                values
        ));
    }

    private boolean llmEnabled() {
        return properties.getCode().getContext().isLlmSummaryEnabled();
    }

    private void detectFrameworks(IndexedFileContext file, Set<String> output) {
        String path = file.path().toLowerCase(Locale.ROOT);
        String content = file.content() == null ? "" : file.content().toLowerCase(Locale.ROOT);
        if (path.endsWith("pom.xml")) output.add("maven");
        if (path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")) output.add("gradle");
        if (path.endsWith("package.json")) output.add("node");
        if (path.endsWith("pubspec.yaml")) output.add("flutter/dart");
        if (path.endsWith(".xaml") || path.endsWith(".xaml.cs")) output.add("wpf/xaml");
        if (content.contains("spring-boot") || content.contains("@springbootapplication")) output.add("spring boot");
        if (content.contains("react") || content.contains("jsx")) output.add("react");
        if (content.contains("statelesswidget") || content.contains("statefulwidget")) output.add("flutter");
    }

    private boolean isEntrypoint(IndexedFileContext file) {
        String path = file.path().toLowerCase(Locale.ROOT);
        String content = file.content() == null ? "" : file.content().toLowerCase(Locale.ROOT);
        return path.endsWith("main.java")
                || path.endsWith("program.cs")
                || path.endsWith("app.jsx")
                || path.endsWith("app.tsx")
                || path.endsWith("main.jsx")
                || path.endsWith("main.tsx")
                || path.endsWith("main.dart")
                || path.endsWith("dockerfile")
                || content.contains("public static void main")
                || content.contains("@springbootapplication");
    }

    private String tree(List<IndexedFileContext> files) {
        int maxDepth = Math.max(1, properties.getCode().getContext().getMaxTreeDepth());
        Map<String, Integer> counts = new TreeMap<>();
        for (IndexedFileContext file : files) {
            String[] parts = file.path().split("/");
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < Math.min(parts.length, maxDepth); i++) {
                if (i > 0) {
                    path.append("/");
                }
                path.append(parts[i]);
                counts.merge(path.toString(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .limit(160)
                .map(entry -> "  ".repeat(depth(entry.getKey())) + "- " + entry.getKey() + " (" + entry.getValue() + ")")
                .collect(Collectors.joining("\n"));
    }

    private String layerHints(List<IndexedFileContext> files) {
        Map<String, Long> layers = files.stream()
                .collect(Collectors.groupingBy(file -> role(file.path()), TreeMap::new, Collectors.counting()));
        return joinMap(layers);
    }

    private int fileImportance(IndexedFileContext file) {
        String path = file.path().toLowerCase(Locale.ROOT);
        int score = file.chunks().size();
        if (isEntrypoint(file)) score += 50;
        if (path.contains("controller") || path.contains("/web/")) score += 35;
        if (path.contains("/service/")) score += 30;
        if (path.contains("/repository/")) score += 25;
        if (path.contains("/config/") || path.contains("/security/")) score += 20;
        if (path.endsWith("readme.md") || path.endsWith("pom.xml") || path.endsWith("package.json")) score += 30;
        return score;
    }

    private String role(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.contains("/web/") || lower.contains("controller")) return "api/controller layer";
        if (lower.contains("/service/")) return "service/business logic layer";
        if (lower.contains("/repository/")) return "database/repository layer";
        if (lower.contains("/security/")) return "security/auth layer";
        if (lower.contains("/config/")) return "configuration layer";
        if (lower.contains("/dto/")) return "request/response dto layer";
        if (lower.startsWith("frontend/") || lower.contains("/components/") || lower.contains("/pages/")) return "frontend/ui layer";
        if (lower.endsWith(".xaml")) return "wpf ui markup";
        if (lower.endsWith("pom.xml") || lower.endsWith("package.json") || lower.endsWith("pubspec.yaml")) return "build/dependency manifest";
        return "supporting code";
    }

    private String roleKeywords(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.contains("/web/") || lower.contains("controller")) {
            return "api endpoint request response route http validation controller";
        }
        if (lower.contains("/service/")) {
            return "business logic workflow transaction validation orchestration service";
        }
        if (lower.contains("/repository/")) {
            return "query database persistence sql jdbc storage repository";
        }
        if (lower.contains("/security/")) {
            return "security auth authorization filter token session permission";
        }
        if (lower.contains("/config/")) {
            return "config property bean setting wiring environment";
        }
        if (lower.contains("/dto/")) {
            return "request response payload dto contract validation";
        }
        if (lower.startsWith("frontend/") || lower.contains("/components/") || lower.contains("/pages/")) {
            return "page component state route ui event form frontend";
        }
        if (lower.contains("/domain/") || lower.contains("/entity/")) {
            return "domain entity model state value persistence";
        }
        if (lower.contains("/test/") || lower.endsWith("test.java") || lower.endsWith(".spec.js") || lower.endsWith(".test.js")) {
            return "test scenario assertion regression coverage";
        }
        if (lower.endsWith("pom.xml") || lower.endsWith("package.json") || lower.endsWith("pubspec.yaml")) {
            return "build dependency manifest script plugin version";
        }
        return "supporting code utility helper integration";
    }

    private String topDirectory(String path) {
        if (path == null || path.isBlank()) {
            return ".";
        }
        int slash = path.indexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private List<String> symbols(List<ParsedCodeChunk> chunks, int limit) {
        return chunks.stream()
                .map(chunk -> firstNonBlank(chunk.methodName(), chunk.className(), chunk.symbolName(), chunk.controlName(), chunk.eventName()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private String chunkTypes(List<ParsedCodeChunk> chunks) {
        Map<String, Long> types = chunks.stream()
                .collect(Collectors.groupingBy(ParsedCodeChunk::chunkType, TreeMap::new, Collectors.counting()));
        return joinMap(types);
    }

    private String joinMap(Map<String, ? extends Number> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String joinOrDash(Iterable<String> values) {
        if (values == null) {
            return "-";
        }
        List<String> clean = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                clean.add(value);
            }
        }
        return clean.isEmpty() ? "-" : String.join(", ", clean);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private int lineCount(String value) {
        return value == null || value.isBlank() ? 1 : value.split("\\R", -1).length;
    }

    private int depth(String path) {
        return path == null || path.isBlank() ? 0 : Math.max(0, path.split("/").length - 1);
    }

    private String trim(String value, int maxChars) {
        String clean = value == null ? "" : value.strip();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars).strip() + "\n...";
    }

    private String maskSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?i)(password\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(credential\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]");
    }

    public record IndexedFileContext(
            String path,
            String language,
            String content,
            List<ParsedCodeChunk> chunks
    ) {
    }

    private record ProjectFacts(
            List<IndexedFileContext> files,
            Map<String, Long> languages,
            Set<String> frameworks,
            Set<String> entrypoints,
            List<DirectoryFacts> directories,
            String tree
    ) {
    }

    private record DirectoryFacts(
            String path,
            int fileCount,
            Map<String, Long> languages,
            List<String> representativeFiles,
            List<String> symbols,
            String responsibility,
            int score
    ) {
        String shortText() {
            return path + ": files=" + fileCount + ", languages=" + languages + ", symbols=" + symbols;
        }
    }

    private static class DirectoryAccumulator {
        private final String path;
        private final List<IndexedFileContext> files = new ArrayList<>();

        DirectoryAccumulator(String path) {
            this.path = path;
        }

        void add(IndexedFileContext file) {
            files.add(file);
        }

        DirectoryFacts toFacts() {
            Map<String, Long> languages = files.stream()
                    .collect(Collectors.groupingBy(IndexedFileContext::language, TreeMap::new, Collectors.counting()));
            List<String> representativeFiles = files.stream()
                    .map(IndexedFileContext::path)
                    .limit(12)
                    .toList();
            List<String> symbols = files.stream()
                    .flatMap(file -> file.chunks().stream())
                    .map(chunk -> {
                        if (chunk.methodName() != null && !chunk.methodName().isBlank()) return chunk.methodName();
                        if (chunk.className() != null && !chunk.className().isBlank()) return chunk.className();
                        return chunk.symbolName();
                    })
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .limit(16)
                    .toList();
            String responsibility = inferResponsibility(path, representativeFiles);
            int score = files.size() + symbols.size();
            return new DirectoryFacts(path, files.size(), languages, representativeFiles, symbols, responsibility, score);
        }

        private String inferResponsibility(String path, List<String> files) {
            String joined = (path + " " + String.join(" ", files)).toLowerCase(Locale.ROOT);
            if (joined.contains("controller") || joined.contains("/web/")) return "api/controller layer";
            if (joined.contains("service")) return "service/business logic layer";
            if (joined.contains("repository")) return "database/repository layer";
            if (joined.contains("security") || joined.contains("auth")) return "security/auth layer";
            if (joined.contains("frontend") || joined.contains("component")) return "frontend/ui layer";
            if (joined.contains("config")) return "configuration layer";
            return "project module";
        }
    }

    private record HybridText(String content, String generatedBy, boolean llmSucceeded) {
    }
}
