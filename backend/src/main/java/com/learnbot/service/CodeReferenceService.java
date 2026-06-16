package com.learnbot.service;

import com.learnbot.dto.CodeReferenceResponse;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeReferenceService {
    private final CodeRepository repository;

    public CodeReferenceService(CodeRepository repository) {
        this.repository = repository;
    }

    public CodeReferenceResponse findReferences(UUID repositoryId, String symbol, Integer limit) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("찾을 심볼명을 입력하세요.");
        }
        String cleanSymbol = symbol.trim();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        List<CodeSearchResult> definitions = repository.findSymbolDefinitions(repositoryId, cleanSymbol, Math.min(safeLimit, 12));
        List<CodeSearchResult> references = withoutDuplicates(
                repository.findSymbolReferences(repositoryId, cleanSymbol, safeLimit),
                definitions
        );
        return new CodeReferenceResponse(cleanSymbol, definitions, references);
    }

    private List<CodeSearchResult> withoutDuplicates(List<CodeSearchResult> references, List<CodeSearchResult> definitions) {
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        for (CodeSearchResult reference : references) {
            merged.put(reference.chunkId(), reference);
        }
        for (CodeSearchResult definition : definitions) {
            merged.remove(definition.chunkId());
        }
        return merged.values().stream().toList();
    }
}
