package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {
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
            if (texts.size() <= minBatchSize) {
                throw ex;
            }
            int nextBatchSize = Math.max(minBatchSize, batchSize / 2);
            if (nextBatchSize >= texts.size()) {
                nextBatchSize = Math.max(minBatchSize, texts.size() / 2);
            }
            List<List<Double>> embeddings = new ArrayList<>();
            for (int start = 0; start < texts.size(); start += nextBatchSize) {
                int end = Math.min(start + nextBatchSize, texts.size());
                embeddings.addAll(embedBatch(texts.subList(start, end), nextBatchSize, minBatchSize));
            }
            return embeddings;
        }
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
