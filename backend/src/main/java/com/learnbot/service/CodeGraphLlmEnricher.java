package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;

@Component
public class CodeGraphLlmEnricher {
    private static final Set<String> ALLOWED_TYPES = Set.of("CALLS", "INJECTS", "USES_ENTITY");
    private static final int MAX_CANDIDATES = 160;
    private static final int MAX_CODE_CHARS_PER_CANDIDATE = 360;
    private static final int MAX_BATCH_JSON_CHARS = 8_000;
    private static final int MAX_LLM_BATCHES = 6;
    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final LearnBotProperties properties;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public CodeGraphLlmEnricher(LearnBotProperties properties, OllamaClient ollamaClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public CodeGraph enrich(CodeGraph graph, List<CodeSearchResult> chunks) {
        return enrichWithDiagnostics(graph, chunks).graph();
    }

    public CodeGraphAnalysisResult enrichWithDiagnostics(CodeGraph graph, List<CodeSearchResult> chunks) {
        long started = System.nanoTime();
        if (!properties.getCode().getGraph().isLlmRelationEnabled() || graph == null
                || properties.getCode().getGraph().getMaxLlmFiles() <= 0) {
            return new CodeGraphAnalysisResult(graph, CodeAnalysisDiagnostic.skipped(
                    "LLM_ENRICHMENT", "Ollama auxiliary", "ASYNC", "LLM relationship enrichment is disabled."
            ));
        }
        List<CodeGraphEdge> candidates = graph.edges().stream()
                .filter(edge -> "REFERENCES".equals(edge.type()))
                .filter(edge -> "deterministic_text_reference".equals(String.valueOf(edge.metadata().get("source"))))
                .filter(edge -> graph.edges().stream().noneMatch(existing -> "CALLS".equals(existing.type())
                        && existing.sourceKey().equals(edge.sourceKey()) && existing.targetKey().equals(edge.targetKey())))
                .limit(MAX_CANDIDATES)
                .toList();
        if (candidates.isEmpty()) {
            return new CodeGraphAnalysisResult(graph, CodeAnalysisDiagnostic.skipped(
                    "LLM_ENRICHMENT", "Ollama auxiliary", "ASYNC", "No unresolved relationship candidates found."
            ));
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
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> currentBatch = new ArrayList<>();
        int inputJsonChars = 2;
        for (CodeGraphEdge edge : candidates) {
            CodeSearchResult evidence = byId.get(edge.evidenceChunkId());
            if (evidence == null || !allowedFiles.contains(evidence.filePath())) {
                continue;
            }
            Map<String, Object> item = Map.of(
                    "sourceKey", edge.sourceKey(),
                    "targetKey", edge.targetKey(),
                    "file", evidence.filePath(),
                    "code", truncate(evidence.content(), MAX_CODE_CHARS_PER_CANDIDATE)
            );
            int nextInputJsonChars = inputJsonChars + estimatedJsonChars(item) + 1;
            if (nextInputJsonChars > MAX_BATCH_JSON_CHARS && !currentBatch.isEmpty()) {
                batches.add(currentBatch);
                if (batches.size() >= MAX_LLM_BATCHES) {
                    break;
                }
                currentBatch = new ArrayList<>();
                inputJsonChars = 2;
                nextInputJsonChars = inputJsonChars + estimatedJsonChars(item) + 1;
            }
            currentBatch.add(item);
            inputJsonChars = nextInputJsonChars;
        }
        if (!currentBatch.isEmpty() && batches.size() < MAX_LLM_BATCHES) {
            batches.add(currentBatch);
        }
        if (batches.isEmpty()) {
            return new CodeGraphAnalysisResult(graph, CodeAnalysisDiagnostic.skipped(
                    "LLM_ENRICHMENT", "Ollama auxiliary", "ASYNC", "No eligible evidence files found."
            ));
        }
        try {
            List<LlmRelation> relations = new ArrayList<>();
            int attempted = 0;
            for (List<Map<String, Object>> batch : batches) {
                attempted += batch.size();
                String response = ollamaClient.chatResult(
                        "Classify unresolved source-code graph candidates. Return JSON only as {\"relations\":[{\"sourceKey\":\"...\",\"targetKey\":\"...\",\"type\":\"CALLS|INJECTS|USES_ENTITY\"}]}. "
                                + "Use only supplied keys. Omit uncertain relations.",
                        objectMapper.writeValueAsString(batch),
                        OllamaClient.ChatRole.AUXILIARY,
                        MAX_OUTPUT_TOKENS,
                        Duration.ofSeconds(properties.getCode().getGraph().getLlmTimeoutSeconds())
                ).content();
                LlmOutput output = objectMapper.readValue(jsonObject(response), LlmOutput.class);
                if (output != null && output.relations() != null) {
                    relations.addAll(output.relations());
                }
            }
            CodeGraph enriched = mergeValidated(graph, candidates, new LlmOutput(relations));
            int added = Math.max(0, enriched.edges().size() - graph.edges().size());
            return new CodeGraphAnalysisResult(enriched, new CodeAnalysisDiagnostic(
                    "LLM_ENRICHMENT", "Ollama auxiliary", "SUCCESS", "ASYNC",
                    attempted, attempted, 0, added, Math.max(0, attempted - added),
                    enriched.nodes().size(), added,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    "LLM relationship enrichment completed.", Map.of("candidateCount", attempted, "batchCount", batches.size())
            ));
        } catch (RuntimeException | java.io.IOException ex) {
            int attempted = batches.stream().mapToInt(List::size).sum();
            return new CodeGraphAnalysisResult(graph, new CodeAnalysisDiagnostic(
                    "LLM_ENRICHMENT", "Ollama auxiliary", "FAILED", "ASYNC",
                    attempted, 0, attempted, 0, attempted, graph.nodes().size(), 0,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    "LLM enrichment failed: " + failureMessage(ex), Map.of()
            ));
        }
    }

    private String failureMessage(Exception ex) {
        if (ex instanceof WebClientResponseException responseException) {
            String body = truncate(responseException.getResponseBodyAsString(), 240);
            return responseException.getStatusCode() + (body.isBlank() ? "" : ": " + body);
        }
        return ex.getClass().getSimpleName();
    }

    private int estimatedJsonChars(Map<String, Object> item) {
        int length = 64;
        for (Object value : item.values()) {
            length += String.valueOf(value).length();
        }
        return length;
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
