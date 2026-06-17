package com.learnbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.learnbot.config.LearnBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OllamaClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final WebClient webClient;
    private final LearnBotProperties properties;

    public OllamaClient(WebClient.Builder builder, LearnBotProperties properties) {
        this.properties = properties;
        this.webClient = builder.baseUrl(properties.getOllama().getBaseUrl()).build();
    }

    public List<List<Double>> embed(List<String> inputs) {
        try {
            EmbedResponse response = webClient.post()
                    .uri("/api/embed")
                    .bodyValue(Map.of(
                            "model", properties.getOllama().getEmbeddingModel(),
                            "input", inputs
                    ))
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .block();

            if (response == null || response.embeddings() == null || response.embeddings().isEmpty()) {
                throw new IllegalArgumentException("Ollama returned no embeddings.");
            }
            return response.embeddings();
        } catch (WebClientResponseException.NotFound ex) {
            return inputs.stream().map(this::embedLegacy).toList();
        }
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chatResult(systemPrompt, userPrompt).content();
    }

    public ChatResult chatResult(String systemPrompt, String userPrompt) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", properties.getOllama().getTemperature());
        options.put("num_ctx", properties.getOllama().getContextWindow());
        if (properties.getOllama().getMaxOutputTokens() > 0) {
            options.put("num_predict", properties.getOllama().getMaxOutputTokens());
        }

        ChatResponse response = webClient.post()
                .uri("/api/chat")
                .bodyValue(Map.of(
                        "model", properties.getOllama().getChatModel(),
                        "stream", false,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)
                        ),
                        "options", options
                ))
                .retrieve()
                .bodyToMono(ChatResponse.class)
                .block();

        if (response == null || response.message() == null || response.message().content() == null) {
            throw new IllegalArgumentException("Ollama returned an empty chat response.");
        }
        ChatResult result = new ChatResult(
                response.message().content().trim(),
                response.doneReason(),
                Boolean.TRUE.equals(response.done()),
                response.promptEvalCount() == null ? 0 : response.promptEvalCount(),
                response.evalCount() == null ? 0 : response.evalCount()
        );
        if (result.stoppedByLength()) {
            log.warn("Ollama chat response stopped by length model={} promptTokens={} outputTokens={} contentLength={}",
                    properties.getOllama().getChatModel(),
                    result.promptEvalCount(),
                    result.evalCount(),
                    result.content().length());
        }
        return result;
    }

    private List<Double> embedLegacy(String input) {
        LegacyEmbedResponse response;
        try {
            response = webClient.post()
                    .uri("/api/embeddings")
                    .bodyValue(Map.of(
                            "model", properties.getOllama().getEmbeddingModel(),
                            "prompt", input
                    ))
                    .retrieve()
                    .bodyToMono(LegacyEmbedResponse.class)
                    .block();
        } catch (WebClientResponseException.NotFound ex) {
            throw new IllegalArgumentException("Ollama embedding model '" + properties.getOllama().getEmbeddingModel()
                    + "' was not found. Pull the model before indexing.", ex);
        }

        if (response == null || response.embedding() == null || response.embedding().isEmpty()) {
            throw new IllegalArgumentException("Ollama returned no embedding.");
        }
        return response.embedding();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponse(List<List<Double>> embeddings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LegacyEmbedResponse(List<Double> embedding) {
    }

    public record ChatResult(
            String content,
            String doneReason,
            boolean done,
            int promptEvalCount,
            int evalCount
    ) {
        public boolean stoppedByLength() {
            return "length".equalsIgnoreCase(doneReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(
            ChatMessage message,
            @JsonProperty("done_reason") String doneReason,
            Boolean done,
            @JsonProperty("prompt_eval_count") Integer promptEvalCount,
            @JsonProperty("eval_count") Integer evalCount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatMessage(String content) {
    }
}
