package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int MIN_FALLBACK_TEXT_LENGTH = 512;

    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;

    public EmbeddingService(OllamaClient ollamaClient, LearnBotProperties properties) {
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    public List<List<Double>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, properties.getEmbedding().getBatchSize());
        int minBatchSize = Math.max(1, Math.min(batchSize, properties.getEmbedding().getMinBatchSize()));
        List<List<Double>> embeddings = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            embeddings.addAll(embedBatch(texts.subList(start, end), batchSize, minBatchSize));
        }
        validate(embeddings, texts.size());
        return embeddings;
    }

    public List<Double> embedOne(String text) {
        List<List<Double>> embeddings = embed(List.of(text));
        if (embeddings.isEmpty()) {
            throw new IllegalArgumentException("Ollama returned no embeddings.");
        }
        return embeddings.get(0);
    }

    private List<List<Double>> embedBatch(List<String> texts, int batchSize, int minBatchSize) {
        try {
            List<List<Double>> embeddings = ollamaClient.embed(texts);
            validate(embeddings, texts.size());
            return embeddings;
        } catch (RuntimeException ex) {
            if (texts.size() == 1) {
                return embedSingleWithLengthFallback(texts.get(0), ex);
            }
            int nextBatchSize = Math.max(minBatchSize, batchSize / 2);
            if (nextBatchSize >= texts.size()) {
                nextBatchSize = Math.max(1, texts.size() / 2);
            }
            List<List<Double>> embeddings = new ArrayList<>();
            for (int start = 0; start < texts.size(); start += nextBatchSize) {
                int end = Math.min(start + nextBatchSize, texts.size());
                embeddings.addAll(embedBatch(texts.subList(start, end), nextBatchSize, minBatchSize));
            }
            return embeddings;
        }
    }

    private List<List<Double>> embedSingleWithLengthFallback(String text, RuntimeException originalFailure) {
        String source = text == null ? "" : text;
        int nextLength = Math.max(MIN_FALLBACK_TEXT_LENGTH, source.length() / 2);
        while (nextLength < source.length()) {
            String shortened = source.substring(0, nextLength);
            try {
                List<List<Double>> embeddings = ollamaClient.embed(List.of(shortened));
                validate(embeddings, 1);
                log.warn("Embedding input was shortened after Ollama rejected it. originalChars={} embeddedChars={} reason={}",
                        source.length(),
                        shortened.length(),
                        rootMessage(originalFailure));
                return embeddings;
            } catch (RuntimeException retryFailure) {
                if (nextLength <= MIN_FALLBACK_TEXT_LENGTH) {
                    throw retryFailure;
                }
                nextLength = Math.max(MIN_FALLBACK_TEXT_LENGTH, nextLength / 2);
            }
        }
        throw originalFailure;
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public void validate(List<List<Double>> embeddings, int expectedCount) {
        if (embeddings.size() != expectedCount) {
            throw new IllegalArgumentException("Embedding count mismatch. Expected "
                    + expectedCount + " but got " + embeddings.size() + ".");
        }
        for (List<Double> embedding : embeddings) {
            if (embedding.size() != properties.getEmbedding().getDimensions()) {
                throw new IllegalArgumentException("Embedding dimension mismatch. Expected "
                        + properties.getEmbedding().getDimensions() + " but got " + embedding.size()
                        + ". Recreate the vector column and reindex when changing embedding models.");
            }
        }
    }
}
