package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.Locale;
import java.util.Map;

public final class CodeSourceClassifier {
    public static final String SOURCE_MAIN = "main";
    public static final String SOURCE_TEST = "test";
    public static final String SOURCE_DOCS = "docs";
    public static final String SOURCE_CONFIG = "config";
    public static final String SOURCE_GENERATED = "generated";
    public static final String SOURCE_VENDOR = "vendor";

    private CodeSourceClassifier() {
    }

    public static SourceProfile classify(String filePath, String chunkType, String parser) {
        String path = normalizePath(filePath);
        String type = safe(chunkType);
        String strategy = safe(parser);
        return new SourceProfile(
                sourceRole(path, type),
                runtimeRole(path, type),
                domainRole(path),
                parserConfidence(strategy),
                isLocalAgentPath(path)
        );
    }

    public static SourceProfile classify(CodeSearchResult result) {
        Map<String, Object> metadata = result == null || result.metadata() == null ? Map.of() : result.metadata();
        String parser = String.valueOf(metadata.getOrDefault("parser", metadata.getOrDefault("strategy", "")));
        return classify(result == null ? "" : result.filePath(), result == null ? "" : result.chunkType(), parser);
    }

    public static String sourceRole(CodeSearchResult result) {
        if (result != null && result.metadata() != null) {
            Object value = result.metadata().get("sourceRole");
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return classify(result).sourceRole();
    }

    public static String runtimeRole(CodeSearchResult result) {
        if (result != null && result.metadata() != null) {
            Object value = result.metadata().get("runtimeRole");
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return classify(result).runtimeRole();
    }

    public static boolean isLocalAgentEvidence(CodeSearchResult result) {
        if (result != null && result.metadata() != null) {
            Object value = result.metadata().get("localAgentEvidence");
            if (value instanceof Boolean bool) {
                return bool;
            }
        }
        return classify(result).localAgentEvidence();
    }

    private static String sourceRole(String path, String chunkType) {
        if (path.contains("/node_modules/") || path.contains("/vendor/")
                || path.contains("/third_party/") || path.contains("/external/")) {
            return SOURCE_VENDOR;
        }
        if (path.endsWith(".designer.cs") && !path.contains("/bin/") && !path.contains("/obj/")) {
            return SOURCE_MAIN;
        }
        if (path.contains("/generated/") || path.contains("/gen/") || path.contains("/build/") || path.contains("/dist/")
                || path.contains("/target/") || path.contains("/bin/") || path.contains("/obj/")
                || path.endsWith(".g.cs") || path.endsWith(".designer.cs") || path.endsWith(".min.js")) {
            return SOURCE_GENERATED;
        }
        if (path.contains("/test/") || path.contains("/tests/") || path.contains("/spec/") || path.contains("/__tests__/")
                || path.contains("/src/test/") || path.contains("/src/it/") || path.endsWith("test.java")
                || path.endsWith("tests.java") || path.endsWith("test.cs") || path.endsWith("tests.cs")
                || path.endsWith("_test.py") || path.endsWith(".spec.ts") || path.endsWith(".test.ts")
                || path.endsWith(".spec.js") || path.endsWith(".test.js")) {
            return SOURCE_TEST;
        }
        if (path.endsWith(".md") || path.contains("/docs/") || path.contains("/doc/")) {
            return SOURCE_DOCS;
        }
        if (SOURCE_CONFIG.equals(runtimeRole(path, chunkType))) {
            return SOURCE_CONFIG;
        }
        return SOURCE_MAIN;
    }

    private static String runtimeRole(String path, String chunkType) {
        if (path.contains("/controller") || path.contains("/controllers/") || path.contains("/web/")
                || path.contains("/routes/") || path.contains("/router/") || path.contains("/endpoint")
                || path.contains("/views/") || path.contains("/pages/") || path.contains("/components/")) {
            return "controller";
        }
        if (path.contains("/service") || path.contains("/services/") || path.contains("/usecase")
                || path.contains("/usecases/") || path.contains("/application/")) {
            return "service";
        }
        if (path.contains("/repository") || path.contains("/repositories/") || path.contains("/dao/")
                || path.contains("/db/") || path.contains("/database/") || path.contains("/persistence/")) {
            return "repository";
        }
        if (path.contains("/model") || path.contains("/models/") || path.contains("/entity")
                || path.contains("/entities/") || path.contains("/domain/") || path.contains("/dto/")) {
            return "model";
        }
        if (path.contains("/config/") || path.contains("/configuration/") || path.endsWith(".yml")
                || path.endsWith(".yaml") || path.endsWith(".json") || path.endsWith(".xml")
                || path.endsWith(".config") || path.endsWith(".csproj") || path.endsWith(".sln")) {
            return SOURCE_CONFIG;
        }
        if ("project_structure".equals(chunkType) || "repository_summary".equals(chunkType)
                || "directory_summary".equals(chunkType) || "file_summary".equals(chunkType)) {
            return "project_context";
        }
        return "unknown";
    }

    private static String domainRole(String path) {
        if (isLocalAgentPath(path)) {
            return "local_agent";
        }
        if (path.contains("coderag") || path.contains("code-rag") || path.contains("/code/")) {
            return "code_rag";
        }
        if (path.contains("rag") || path.contains("embedding") || path.contains("index")) {
            return "rag";
        }
        return "application";
    }

    private static double parserConfidence(String parser) {
        return switch (parser) {
            case "javaparser", "roslyn_semantic_model" -> 1.0;
            case "regex", "xml_regex" -> 0.72;
            case "regex_symbol", "statement", "top_level_block", "markdown_heading" -> 0.58;
            case "line_window" -> 0.35;
            default -> 0.50;
        };
    }

    private static boolean isLocalAgentPath(String path) {
        return path.contains("local-agent") || path.contains("localagent")
                || path.contains("/local-agents/") || path.contains("/agentloop/")
                || path.contains("/code-agent/");
    }

    private static String normalizePath(String value) {
        return safe(value).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record SourceProfile(
            String sourceRole,
            String runtimeRole,
            String domainRole,
            double parserConfidence,
            boolean localAgentEvidence
    ) {
    }
}
