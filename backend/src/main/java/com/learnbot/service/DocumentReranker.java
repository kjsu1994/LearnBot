package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.SearchResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class DocumentReranker {
    private final LearnBotProperties properties;
    private final WebClient.Builder webClientBuilder;

    public DocumentReranker(LearnBotProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public List<SearchResult> rerank(String query, List<SearchResult> candidates) {
        LearnBotProperties.Rag.Pipeline.Reranker config = properties.getRag().getPipeline().getReranker();
        if (!config.isEnabled() || candidates == null || candidates.size() <= 1 || safe(query).isBlank()) {
            return candidates == null ? List.of() : candidates;
        }
        List<SearchResult> limited = candidates.stream()
                .limit(Math.max(1, config.getTopN()))
                .toList();
        try {
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
                                            trim(safe(result.content()), 2400)))
                                    .toList()))
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .block(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
            if (response == null || response.results() == null || response.results().isEmpty()) {
                return candidates;
            }
            Map<UUID, Double> scores = response.results().stream()
                    .collect(Collectors.toMap(
                            result -> UUID.fromString(result.id()),
                            RerankResult::score,
                            Math::max,
                            LinkedHashMap::new));
            List<SearchResult> reranked = limited.stream()
                    .map(result -> withRerankScore(result, scores.get(result.chunkId())))
                    .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                    .toList();
            if (candidates.size() <= limited.size()) {
                return reranked;
            }
            return java.util.stream.Stream.concat(reranked.stream(), candidates.stream().skip(limited.size()))
                    .toList();
        } catch (RuntimeException ex) {
            return candidates;
        }
    }

    private SearchResult withRerankScore(SearchResult result, Double rerankScore) {
        if (rerankScore == null) {
            return result;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("rerankerScore", rerankScore);
        metadata.put("rerankerUsed", true);
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
