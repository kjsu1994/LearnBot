package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CodeGraphLlmEnricher {
    private static final Set<String> ALLOWED_TYPES = Set.of("CALLS", "INJECTS", "USES_ENTITY");
    private static final int MAX_CANDIDATES = 160;

    private final LearnBotProperties properties;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public CodeGraphLlmEnricher(LearnBotProperties properties, OllamaClient ollamaClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public CodeGraph enrich(CodeGraph graph, List<CodeSearchResult> chunks) {
        if (!properties.getCode().getGraph().isLlmRelationEnabled() || graph == null
                || properties.getCode().getGraph().getMaxLlmFiles() <= 0) {
            return graph;
        }
        List<CodeGraphEdge> candidates = graph.edges().stream()
                .filter(edge -> "REFERENCES".equals(edge.type()))
                .filter(edge -> "deterministic_text_reference".equals(String.valueOf(edge.metadata().get("source"))))
                .filter(edge -> graph.edges().stream().noneMatch(existing -> "CALLS".equals(existing.type())
                        && existing.sourceKey().equals(edge.sourceKey()) && existing.targetKey().equals(edge.targetKey())))
                .limit(MAX_CANDIDATES)
                .toList();
        if (candidates.isEmpty()) {
            return graph;
        }
        Set<String> allowedFiles = new LinkedHashSet<>();
        Map<UUID, CodeSearchResult> byId = new LinkedHashMap<>();
        if (chunks != null) {
            for (CodeSearchResult chunk : chunks) {
                byId.put(chunk.chunkId(), chunk);
                if (allowedFiles.size() < properties.getCode().getGraph().getMaxLlmFiles()) {
                    allowedFiles.add(chunk.filePath());
                }
            }
        }
        List<Map<String, Object>> input = new ArrayList<>();
        for (CodeGraphEdge edge : candidates) {
            CodeSearchResult evidence = byId.get(edge.evidenceChunkId());
            if (evidence == null || !allowedFiles.contains(evidence.filePath())) {
                continue;
            }
            input.add(Map.of(
                    "sourceKey", edge.sourceKey(),
                    "targetKey", edge.targetKey(),
                    "file", evidence.filePath(),
                    "code", truncate(evidence.content(), 1200)
            ));
        }
        if (input.isEmpty()) {
            return graph;
        }
        try {
            String response = ollamaClient.chat(
                    "Classify unresolved source-code graph candidates. Return JSON only as {\"relations\":[{\"sourceKey\":\"...\",\"targetKey\":\"...\",\"type\":\"CALLS|INJECTS|USES_ENTITY\"}]}. "
                            + "Use only supplied keys. Omit uncertain relations.",
                    objectMapper.writeValueAsString(input),
                    OllamaClient.ChatRole.AUXILIARY
            );
            LlmOutput output = objectMapper.readValue(jsonObject(response), LlmOutput.class);
            return mergeValidated(graph, candidates, output);
        } catch (RuntimeException | java.io.IOException ignored) {
            return graph;
        }
    }

    private CodeGraph mergeValidated(CodeGraph graph, List<CodeGraphEdge> candidates, LlmOutput output) {
        if (output == null || output.relations() == null) {
            return graph;
        }
        Map<String, CodeGraphEdge> candidateMap = new LinkedHashMap<>();
        candidates.forEach(edge -> candidateMap.put(edge.sourceKey() + "|" + edge.targetKey(), edge));
        Map<String, CodeGraphEdge> edges = new LinkedHashMap<>();
        graph.edges().forEach(edge -> edges.put(edge.sourceKey() + "|" + edge.type() + "|" + edge.targetKey(), edge));
        for (LlmRelation relation : output.relations()) {
            if (relation == null || !ALLOWED_TYPES.contains(relation.type())) {
                continue;
            }
            CodeGraphEdge candidate = candidateMap.get(relation.sourceKey() + "|" + relation.targetKey());
            if (candidate == null) {
                continue;
            }
            String edgeKey = relation.sourceKey() + "|" + relation.type() + "|" + relation.targetKey();
            edges.putIfAbsent(edgeKey, new CodeGraphEdge(
                    relation.sourceKey(), relation.targetKey(), relation.type(), 0.52,
                    candidate.evidenceChunkId(), Map.of("source", "llm_fallback", "provisional", true)
            ));
        }
        return new CodeGraph(graph.nodes(), List.copyOf(edges.values()));
    }

    private String jsonObject(String value) {
        if (value == null) {
            return "{}";
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end >= start ? value.substring(start, end + 1) : "{}";
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max);
    }

    private record LlmOutput(List<LlmRelation> relations) {}
    private record LlmRelation(String sourceKey, String targetKey, String type) {}
}
