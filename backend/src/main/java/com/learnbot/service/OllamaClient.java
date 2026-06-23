package com.learnbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.learnbot.config.LearnBotProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.LinkedHashMap;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OllamaClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final WebClient.Builder webClientBuilder;
    private final WebClient webClient;
    private final LearnBotProperties properties;
    private final AdminSettingsService adminSettingsService;
    private final RuntimeTuningService runtimeTuningService;
    private final AtomicInteger primaryRequests = new AtomicInteger(0);
    private final AtomicInteger embeddingRequests = new AtomicInteger(0);

    @Autowired
    public OllamaClient(
            WebClient.Builder builder,
            LearnBotProperties properties,
            AdminSettingsService adminSettingsService,
            RuntimeTuningService runtimeTuningService
    ) {
        this.webClientBuilder = builder;
        this.properties = properties;
        this.adminSettingsService = adminSettingsService;
        this.runtimeTuningService = runtimeTuningService;
        this.webClient = builder.baseUrl(properties.getOllama().getBaseUrl()).build();
    }

    public OllamaClient(WebClient.Builder builder, LearnBotProperties properties, AdminSettingsService adminSettingsService) {
        this(builder, properties, adminSettingsService, null);
    }

    public List<List<Double>> embed(List<String> inputs) {
        embeddingRequests.incrementAndGet();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getOllama().getEmbeddingModel());
            body.put("input", inputs);
            putIfConfigured(body, "keep_alive", properties.getOllama().getEmbeddingKeepAlive());
            EmbedResponse response = webClient.post()
                    .uri("/api/embed")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .block();

            if (response == null || response.embeddings() == null || response.embeddings().isEmpty()) {
                throw new IllegalArgumentException("Ollama returned no embeddings.");
            }
            return response.embeddings();
        } catch (WebClientResponseException.NotFound ex) {
            return inputs.stream().map(this::embedLegacy).toList();
        } finally {
            embeddingRequests.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chatResult(systemPrompt, userPrompt).content();
    }

    public String chat(String systemPrompt, String userPrompt, ChatRole role) {
        return chatResult(systemPrompt, userPrompt, role).content();
    }

    public String chat(String systemPrompt, String userPrompt, ChatRole role, int maxOutputTokens) {
        return chatResult(systemPrompt, userPrompt, role, maxOutputTokens).content();
    }

    public ChatResult chatResult(String systemPrompt, String userPrompt) {
        return chatResult(systemPrompt, userPrompt, ChatRole.PRIMARY);
    }

    public ChatResult chatResult(String systemPrompt, String userPrompt, ChatRole role) {
        return chatResult(systemPrompt, userPrompt, role, null);
    }

    public ChatResult chatResult(String systemPrompt, String userPrompt, int maxOutputTokens) {
        return chatResult(systemPrompt, userPrompt, ChatRole.PRIMARY, maxOutputTokens);
    }

    public ChatResult chatResult(String systemPrompt, String userPrompt, ChatRole role, Integer maxOutputTokens) {
        return chatResult(systemPrompt, userPrompt, role, maxOutputTokens, null);
    }

    public ChatResult chatResult(String systemPrompt, String userPrompt, ChatRole role, Integer maxOutputTokens, Duration timeout) {
        List<AdminSettingsService.LlmSettings> candidates = candidates(role);
        RuntimeException lastFailure = null;
        for (int index = 0; index < candidates.size(); index++) {
            AdminSettingsService.LlmSettings settings = candidates.get(index);
            try {
                return chatResultWith(settings, systemPrompt, userPrompt, index > 0, maxOutputTokens, timeout);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (index < candidates.size() - 1) {
                    AdminSettingsService.LlmSettings next = candidates.get(index + 1);
                    log.warn("Ollama chat failed. Falling back role={} failedRole={} failedBaseUrl={} failedModel={} nextRole={} nextBaseUrl={} nextModel={} reason={}",
                            role,
                            settings.role(),
                            settings.baseUrl(),
                            settings.model(),
                            next.role(),
                            next.baseUrl(),
                            next.model(),
                            ex.getClass().getSimpleName());
                }
            }
        }
        throw lastFailure == null ? new IllegalArgumentException("Ollama chat failed.") : lastFailure;
    }

    public void beginPrimaryRequest() {
        primaryRequests.incrementAndGet();
    }

    public void finishPrimaryRequest() {
        primaryRequests.updateAndGet(current -> Math.max(0, current - 1));
    }

    public boolean hasPrimaryRequestInFlight() {
        return primaryRequests.get() > 0;
    }

    public int primaryRequestCount() {
        return primaryRequests.get();
    }

    public int embeddingRequestCount() {
        return embeddingRequests.get();
    }

    private ChatResult chatResultWith(AdminSettingsService.LlmSettings settings, String systemPrompt, String userPrompt, boolean fallbackUsed, Integer maxOutputTokens, Duration timeout) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", properties.getOllama().getTemperature());
        options.put("num_ctx", effectiveContextWindow());
        int requestedMaxOutputTokens = maxOutputTokens == null ? effectiveMaxOutputTokens() : maxOutputTokens;
        if (requestedMaxOutputTokens > 0) {
            options.put("num_predict", requestedMaxOutputTokens);
        }
        String keepAlive = keepAliveFor(settings.role());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("options", options);
        putIfConfigured(body, "keep_alive", keepAlive);

        var responseMono = webClientBuilder.clone()
                .baseUrl(settings.baseUrl())
                .build()
                .post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ChatResponse.class);
        ChatResponse response = timeout == null ? responseMono.block() : responseMono.block(timeout);

        if (response == null || response.message() == null || response.message().content() == null) {
            throw new IllegalArgumentException("Ollama returned an empty chat response.");
        }
        ChatResult result = new ChatResult(
                response.message().content().trim(),
                response.doneReason(),
                Boolean.TRUE.equals(response.done()),
                response.promptEvalCount() == null ? 0 : response.promptEvalCount(),
                response.evalCount() == null ? 0 : response.evalCount(),
                settings.baseUrl(),
                settings.model(),
                settings.role(),
                fallbackUsed
        );
        if (result.stoppedByLength()) {
            log.warn("Ollama chat response stopped by length model={} baseUrl={} promptTokens={} outputTokens={} contentLength={}",
                    result.model(),
                    result.baseUrl(),
                    result.promptEvalCount(),
                    result.evalCount(),
                    result.content().length());
        }
        log.info("Ollama chat completed role={} model={} baseUrl={} keepAlive={} numCtx={} promptTokens={} outputTokens={} doneReason={}",
                result.role(),
                result.model(),
                result.baseUrl(),
                keepAlive == null || keepAlive.isBlank() ? "daemon-default" : keepAlive,
                options.get("num_ctx"),
                result.promptEvalCount(),
                result.evalCount(),
                result.doneReason());
        return result;
    }

    private int effectiveContextWindow() {
        return runtimeTuningService == null ? properties.getOllama().getContextWindow() : runtimeTuningService.ollamaContextLength();
    }

    private int effectiveMaxOutputTokens() {
        return runtimeTuningService == null ? properties.getOllama().getMaxOutputTokens() : runtimeTuningService.llmMaxOutputTokens();
    }

    private String keepAliveFor(String role) {
        if ("auxiliary".equalsIgnoreCase(role)) {
            return properties.getOllama().getAuxiliaryKeepAlive();
        }
        return properties.getOllama().getPrimaryKeepAlive();
    }

    private void putIfConfigured(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value.trim());
        }
    }

    private List<Double> embedLegacy(String input) {
        LegacyEmbedResponse response;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getOllama().getEmbeddingModel());
            body.put("prompt", input);
            putIfConfigured(body, "keep_alive", properties.getOllama().getEmbeddingKeepAlive());
            response = webClient.post()
                    .uri("/api/embeddings")
                    .bodyValue(body)
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
            int evalCount,
            String baseUrl,
            String model,
            String role,
            boolean fallbackUsed
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

    private List<AdminSettingsService.LlmSettings> candidates(ChatRole role) {
        List<AdminSettingsService.LlmSettings> candidates = new ArrayList<>();
        if (role == ChatRole.PRIMARY) {
            addCandidate(candidates, adminSettingsService.primaryLlmSettings());
            addCandidate(candidates, adminSettingsService.auxiliaryLlmSettings());
        } else {
            addCandidate(candidates, adminSettingsService.auxiliaryLlmSettings());
        }
        return candidates;
    }

    private void addCandidate(List<AdminSettingsService.LlmSettings> candidates, AdminSettingsService.LlmSettings next) {
        boolean duplicate = candidates.stream()
                .anyMatch(current -> current.baseUrl().equals(next.baseUrl()) && current.model().equals(next.model()));
        if (!duplicate) {
            candidates.add(next);
        }
    }

    public enum ChatRole {
        PRIMARY,
        AUXILIARY
    }
}
