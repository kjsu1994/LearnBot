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
import java.time.Duration;

@Component
public class CodeGraphLlmEnricher {
    private static final Set<String> ALLOWED_TYPES = Set.of("CALLS", "INJECTS", "USES_ENTITY");
    private static final int MAX_CANDIDATES = 160;
    private static final int MAX_CODE_CHARS_PER_CANDIDATE = 360;

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
            if (nextInputJsonChars > maxBatchJsonChars() && !currentBatch.isEmpty()) {
                batches.add(currentBatch);
                if (batches.size() >= maxLlmBatches()) {
                    break;
                }
                currentBatch = new ArrayList<>();
                inputJsonChars = 2;
                nextInputJsonChars = inputJsonChars + estimatedJsonChars(item) + 1;
            }
            currentBatch.add(item);
            inputJsonChars = nextInputJsonChars;
        }
        if (!currentBatch.isEmpty() && batches.size() < maxLlmBatches()) {
            batches.add(currentBatch);
        }
        if (batches.isEmpty()) {
            return new CodeGraphAnalysisResult(graph, CodeAnalysisDiagnostic.skipped(
                    "LLM_ENRICHMENT", "Ollama auxiliary", "ASYNC", "No eligible evidence files found."
            ));
        }
        List<LlmRelation> relations = new ArrayList<>();
        int attempted = 0;
        int failed = 0;
        for (List<Map<String, Object>> batch : batches) {
            attempted += batch.size();
            try {
                relations.addAll(classifyBatch(batch));
            } catch (RuntimeException | java.io.IOException ex) {
                if (batch.size() <= 1) {
                    failed += batch.size();
                    continue;
                }
                for (List<Map<String, Object>> retryBatch : splitBatch(batch)) {
                    try {
                        relations.addAll(classifyBatch(retryBatch));
                    } catch (RuntimeException | java.io.IOException retryEx) {
                        failed += retryBatch.size();
                    }
                }
            }
        }
        CodeGraph enriched = mergeValidated(graph, candidates, new LlmOutput(relations));
        int added = Math.max(0, enriched.edges().size() - graph.edges().size());
        String status = failed == 0 ? "SUCCESS" : added > 0 ? "PARTIAL" : "FAILED";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("candidateCount", attempted);
        metadata.put("batchCount", batches.size());
        metadata.put("failedCandidates", failed);
        metadata.put("maxOutputTokens", maxOutputTokens());
        metadata.put("maxBatchJsonChars", maxBatchJsonChars());
        return new CodeGraphAnalysisResult(enriched, new CodeAnalysisDiagnostic(
                "LLM_ENRICHMENT", "Ollama auxiliary", status, "ASYNC",
                attempted, Math.max(0, attempted - failed), failed, added, Math.max(0, attempted - added),
                enriched.nodes().size(), added,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                failed == 0 ? "LLM relationship enrichment completed." : "LLM relationship enrichment completed partially.",
                Map.copyOf(metadata)
        ));
    }

    private List<LlmRelation> classifyBatch(List<Map<String, Object>> batch) throws java.io.IOException {
        OllamaClient.ChatResult result = ollamaClient.chatResult(
                "Classify unresolved source-code graph candidates. Return JSON only as {\"relations\":[{\"sourceKey\":\"...\",\"targetKey\":\"...\",\"type\":\"CALLS|INJECTS|USES_ENTITY\"}]}. "
                        + "Use only supplied keys. Omit uncertain relations.",
                objectMapper.writeValueAsString(batch),
                OllamaClient.ChatRole.AUXILIARY,
                maxOutputTokens(),
                Duration.ofSeconds(Math.max(1, properties.getCode().getGraph().getLlmTimeoutSeconds()))
        );
        if (result.stoppedByLength()) {
            throw new IllegalStateException("LLM enrichment response stopped by length");
        }
        LlmOutput output = objectMapper.readValue(jsonObject(result.content()), LlmOutput.class);
        return output == null || output.relations() == null ? List.of() : output.relations();
    }

    private List<List<Map<String, Object>>> splitBatch(List<Map<String, Object>> batch) {
        int midpoint = Math.max(1, batch.size() / 2);
        List<List<Map<String, Object>>> split = new ArrayList<>();
        split.add(batch.subList(0, midpoint));
        if (midpoint < batch.size()) {
            split.add(batch.subList(midpoint, batch.size()));
        }
        return split;
    }

    private int maxOutputTokens() {
        return Math.max(1, properties.getCode().getGraph().getLlmMaxOutputTokens());
    }

    private int maxBatchJsonChars() {
        return Math.max(512, properties.getCode().getGraph().getLlmMaxBatchJsonChars());
    }

    private int maxLlmBatches() {
        return Math.max(1, properties.getCode().getGraph().getLlmMaxBatches());
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
