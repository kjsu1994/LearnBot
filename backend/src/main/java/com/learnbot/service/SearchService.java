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
        return search(query, filter, limit, null, null);
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit, List<java.util.UUID> spaceIds, java.util.UUID selectedSpaceId) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<java.util.UUID> safeSpaceIds = spaceIds == null || spaceIds.isEmpty()
                ? List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID)
                : spaceIds;
        List<Double> embedding;
        try {
            embedding = ollamaClient.embed(List.of(query)).get(0);
        } catch (RuntimeException ex) {
            return repository.keywordSearch(query, filter, safeLimit, safeSpaceIds, selectedSpaceId);
        }

        return repository.search(query, embedding, filter, safeLimit, safeSpaceIds, selectedSpaceId);
    }
}
