package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class DocumentReranker {
    private static final Logger log = LoggerFactory.getLogger(DocumentReranker.class);

    private final LearnBotProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final AtomicLong unavailableUntilMillis = new AtomicLong(0);

    public DocumentReranker(LearnBotProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public List<SearchResult> rerank(String query, List<SearchResult> candidates) {
        LearnBotProperties.Rag.Pipeline.Reranker config = properties.getRag().getPipeline().getReranker();
        if (!config.isEnabled() || candidates == null || candidates.size() <= 1 || safe(query).isBlank()) {
            return candidates == null ? List.of() : candidates;
        }
        if (System.currentTimeMillis() < unavailableUntilMillis.get()) {
            return withRerankerStatus(candidates, "skipped_circuit_open");
        }
        List<SearchResult> limited = candidates.stream()
                .limit(Math.max(1, config.getTopN()))
                .filter(result -> usefulText(result).length() >= 24)
                .toList();
        if (limited.size() <= 1) {
            return withRerankerStatus(candidates, "skipped_too_few_usable_candidates");
        }
        try {
            long started = System.nanoTime();
            RerankResponse response = webClientBuilder.clone()
                    .baseUrl(config.getBaseUrl())
                    .build()
                    .post()
                    .uri("/rerank")
                    .bodyValue(new RerankRequest(
                            query,
                            limited.stream()
                                    .map(result -> new RerankDocument(
                                            result.chunkId().toString(),
                                            safe(result.title()),
                                            trim(rerankText(result), 1400)))
                                    .toList()))
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .block(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
            if (response == null || response.results() == null || response.results().isEmpty()) {
                markUnavailable(config, "empty_response", null);
                return withRerankerStatus(candidates, "empty_response");
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            Map<UUID, Double> scores = response.results().stream()
                    .collect(Collectors.toMap(
                            result -> UUID.fromString(result.id()),
                            RerankResult::score,
                            Math::max,
                            LinkedHashMap::new));
            List<SearchResult> reranked = limited.stream()
                    .map(result -> withRerankScore(result, scores.get(result.chunkId()), durationMs))
                    .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                    .toList();
            if (candidates.size() <= limited.size()) {
                return reranked;
            }
            List<SearchResult> output = new ArrayList<>(reranked);
            for (SearchResult candidate : candidates) {
                boolean alreadyIncluded = output.stream().anyMatch(result -> result.chunkId().equals(candidate.chunkId()));
                if (!alreadyIncluded) {
                    output.add(candidate);
                }
            }
            return output;
        } catch (RuntimeException ex) {
            markUnavailable(config, ex.getClass().getSimpleName(), ex);
            return withRerankerStatus(candidates, "failed_" + ex.getClass().getSimpleName());
        }
    }

    public List<SearchResult> skip(List<SearchResult> candidates, String reason) {
        return withRerankerStatus(candidates == null ? List.of() : candidates, "skipped_" + safe(reason));
    }

    public void warmup() {
        LearnBotProperties.Rag.Pipeline.Reranker config = properties.getRag().getPipeline().getReranker();
        if (!config.isEnabled()) {
            return;
        }
        try {
            webClientBuilder.clone()
                    .baseUrl(config.getBaseUrl())
                    .build()
                .post()
                .uri("/warmup")
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(Math.max(1, Math.min(5, config.getTimeoutSeconds()))));
        } catch (RuntimeException ignored) {
            // Warm-up is best-effort. Runtime rerank calls still fall back to original ranking.
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        Thread thread = new Thread(this::warmup, "document-reranker-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private void markUnavailable(LearnBotProperties.Rag.Pipeline.Reranker config, String reason, RuntimeException ex) {
        long backoffMillis = Math.max(1, config.getFailureBackoffSeconds()) * 1000L;
        unavailableUntilMillis.set(System.currentTimeMillis() + backoffMillis);
        if (ex == null) {
            log.info("document_reranker_unavailable reason={} backoffSeconds={}", reason, config.getFailureBackoffSeconds());
        } else {
            log.warn("document_reranker_unavailable reason={} backoffSeconds={}", reason, config.getFailureBackoffSeconds(), ex);
        }
    }

    private SearchResult withRerankScore(SearchResult result, Double rerankScore, long durationMs) {
        if (rerankScore == null) {
            return result;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("rerankerScore", rerankScore);
        metadata.put("rerankerUsed", true);
        metadata.put("rerankerStatus", "used");
        metadata.put("rerankerDurationMs", durationMs);
        double score = (result.score() * 0.35) + (rerankScore * 0.65);
        return new SearchResult(
                result.chunkId(),
                result.documentId(),
                result.title(),
                result.sourceUri(),
                result.sourceType(),
                result.contentType(),
                result.chunkIndex(),
                result.content(),
                metadata,
                score
        );
    }

    private List<SearchResult> withRerankerStatus(List<SearchResult> candidates, String status) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        return candidates.stream()
                .map(result -> withMetadata(result, Map.of("rerankerStatus", status)))
                .toList();
    }

    private SearchResult withMetadata(SearchResult result, Map<String, Object> additions) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.putAll(additions);
        return new SearchResult(
                result.chunkId(),
                result.documentId(),
                result.title(),
                result.sourceUri(),
                result.sourceType(),
                result.contentType(),
                result.chunkIndex(),
                result.content(),
                metadata,
                result.score()
        );
    }

    private String usefulText(SearchResult result) {
        return safe(result == null ? "" : result.content()).replaceAll("\\s+", " ").trim();
    }

    private String rerankText(SearchResult result) {
        if (result == null) {
            return "";
        }
        List<String> metadata = new ArrayList<>();
        addMetadata(metadata, "source", result.sourceUri());
        addMetadata(metadata, "contentType", result.contentType());
        addMetadata(metadata, "page", metadataString(result, "pageNumber"));
        addMetadata(metadata, "section", metadataString(result, "sectionTitle"));
        addMetadata(metadata, "heading", metadataString(result, "headingPath"));
        addMetadata(metadata, "table", metadataString(result, "tableId"));
        addMetadata(metadata, "documentType", metadataString(result, "documentType"));
        addMetadata(metadata, "schema", metadataString(result, "schemaName"));
        String header = metadata.isEmpty() ? "" : String.join(" | ", metadata) + "\n";
        return header + usefulText(result);
    }

    private void addMetadata(List<String> output, String label, String value) {
        String clean = safe(value).trim();
        if (!clean.isBlank()) {
            output.add(label + "=" + clean);
        }
    }

    private String metadataString(SearchResult result, String key) {
        Object value = result == null || result.metadata() == null ? null : result.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String trim(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record RerankRequest(String query, List<RerankDocument> documents) {
    }

    private record RerankDocument(String id, String title, String text) {
    }

    private record RerankResponse(List<RerankResult> results) {
    }

    private record RerankResult(String id, double score) {
    }
}
