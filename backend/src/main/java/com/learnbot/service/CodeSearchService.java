package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodeSearchService {
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}(?:\\.[A-Za-z0-9_]+)?");

    private final CodeRepository repository;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;

    public CodeSearchService(CodeRepository repository, OllamaClient ollamaClient, LearnBotProperties properties) {
        this.repository = repository;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    public List<CodeSearchResult> search(UUID repositoryId, String query, int limit) {
        return search(repositoryId, query, limit, java.util.List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID), null);
    }

    public List<CodeSearchResult> search(UUID repositoryId, String query, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        int safeLimit = Math.max(1, Math.min(limit, 30));
        List<UUID> safeSpaceIds = spaceIds == null || spaceIds.isEmpty()
                ? java.util.List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID)
                : spaceIds;
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();

        for (CodeSearchResult result : repository.keywordSearch(repositoryId, query, safeLimit, safeSpaceIds, selectedSpaceId)) {
            merge(merged, result);
        }
        for (String identifier : identifiers(query)) {
            for (CodeSearchResult result : repository.keywordSearch(repositoryId, identifier, Math.max(5, safeLimit / 2), safeSpaceIds, selectedSpaceId)) {
                merge(merged, boost(result, 0.18));
            }
        }

        try {
            List<Double> embedding = ollamaClient.embed(List.of(query)).get(0);
            for (CodeSearchResult result : repository.search(repositoryId, query, embedding, safeLimit, safeSpaceIds, selectedSpaceId)) {
                merge(merged, result);
            }
        } catch (RuntimeException ignored) {
            // Keyword and exact identifier search remain available when embeddings are temporarily unavailable.
        }

        List<CodeSearchResult> ranked = merged.values().stream()
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed())
                .limit(safeLimit)
                .toList();
        return expandRelated(repositoryId, ranked, safeLimit);
    }

    private List<CodeSearchResult> expandRelated(UUID repositoryId, List<CodeSearchResult> ranked, int limit) {
        Map<UUID, CodeSearchResult> expanded = new LinkedHashMap<>();
        for (CodeSearchResult result : ranked) {
            merge(expanded, result);
            for (CodeSearchResult related : repository.relatedChunks(
                    result.repositoryId(),
                    relatedPaths(result.filePath()),
                    result.chunkIndex(),
                    4
            )) {
                merge(expanded, related);
            }
        }
        return expanded.values().stream()
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    private List<String> relatedPaths(String filePath) {
        List<String> paths = new ArrayList<>();
        paths.add(filePath);
        if (filePath.endsWith(".xaml")) {
            paths.add(filePath + ".cs");
        } else if (filePath.endsWith(".xaml.cs")) {
            paths.add(filePath.substring(0, filePath.length() - 3));
        } else if (filePath.endsWith(".Designer.cs")) {
            paths.add(filePath.substring(0, filePath.length() - ".Designer.cs".length()) + ".cs");
        }
        return paths;
    }

    private List<String> identifiers(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = IDENTIFIER_PATTERN.matcher(query);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isBlank() && !isCommonWord(value)) {
                values.add(value);
            }
        }
        return values.stream().distinct().limit(8).toList();
    }

    private boolean isCommonWord(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("public") || lower.equals("private") || lower.equals("class") || lower.equals("method");
    }

    private void merge(Map<UUID, CodeSearchResult> merged, CodeSearchResult result) {
        CodeSearchResult current = merged.get(result.chunkId());
        if (current == null || result.score() > current.score()) {
            merged.put(result.chunkId(), result);
        }
    }

    private CodeSearchResult boost(CodeSearchResult result, double value) {
        return new CodeSearchResult(
                result.chunkId(),
                result.repositoryId(),
                result.fileId(),
                result.repositoryName(),
                result.filePath(),
                result.chunkType(),
                result.symbolName(),
                result.className(),
                result.methodName(),
                result.namespaceName(),
                result.controlName(),
                result.eventName(),
                result.chunkIndex(),
                result.lineStart(),
                result.lineEnd(),
                result.content(),
                result.score() + value,
                result.metadata()
        );
    }
}
