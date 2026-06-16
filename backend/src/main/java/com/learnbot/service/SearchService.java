package com.learnbot.service;

import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SearchService {
    private final DocumentRepository repository;
    private final OllamaClient ollamaClient;

    public SearchService(DocumentRepository repository, OllamaClient ollamaClient) {
        this.repository = repository;
        this.ollamaClient = ollamaClient;
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<Double> embedding;
        try {
            embedding = ollamaClient.embed(List.of(query)).get(0);
        } catch (RuntimeException ex) {
            return repository.keywordSearch(query, filter, safeLimit);
        }

        return repository.search(query, embedding, filter, safeLimit);
    }
}
