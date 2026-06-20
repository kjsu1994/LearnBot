package com.learnbot.service;

import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.learnbot.config.LearnBotProperties;

@Service
public class SearchService {
    private final DocumentRepository repository;
    private final OllamaClient ollamaClient;
    private final DocumentReranker documentReranker;
    private final LearnBotProperties properties;
    private final Map<String, CachedEmbedding> embeddingCache = new ConcurrentHashMap<>();

    public SearchService(DocumentRepository repository, OllamaClient ollamaClient) {
        this(repository, ollamaClient, null, null);
    }

    @Autowired
    public SearchService(DocumentRepository repository, OllamaClient ollamaClient, DocumentReranker documentReranker, LearnBotProperties properties) {
        this.repository = repository;
        this.ollamaClient = ollamaClient;
        this.documentReranker = documentReranker;
        this.properties = properties;
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit) {
        return search(query, filter, limit, null, null);
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit, List<java.util.UUID> spaceIds, java.util.UUID selectedSpaceId) {
        return search(query, filter, limit, spaceIds, selectedSpaceId, "BALANCED");
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit, List<java.util.UUID> spaceIds, java.util.UUID selectedSpaceId, String speedProfile) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<java.util.UUID> safeSpaceIds = spaceIds == null || spaceIds.isEmpty()
                ? List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID)
                : spaceIds;
        int candidateLimit = Math.min(60, Math.max(safeLimit * 4, 20));
        Map<UUID, SearchResult> merged = new LinkedHashMap<>();
        List<String> expandedQueries = expandedQueries(query);

        try {
            String semanticQuery = String.join(" ", expandedQueries);
            List<Double> embedding = embeddingFor(semanticQuery);
            for (SearchResult result : repository.search(semanticQuery, embedding, filter, candidateLimit, safeSpaceIds, selectedSpaceId)) {
                merge(merged, result);
            }
        } catch (RuntimeException ex) {
            // Keyword search below remains available when embeddings are temporarily unavailable.
        }

        for (String searchQuery : expandedQueries) {
            int keywordLimit = searchQuery.equalsIgnoreCase(safeQuery(query)) ? candidateLimit : Math.max(8, candidateLimit / 2);
            for (SearchResult result : repository.keywordSearch(searchQuery, filter, keywordLimit, safeSpaceIds, selectedSpaceId)) {
                merge(merged, searchQuery.equalsIgnoreCase(safeQuery(query)) ? result : boost(result, 0.05));
            }
        }

        List<SearchResult> ranked = merged.values().stream()
                .map(result -> boost(result, rerankBoost(query, result)))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .toList();
        List<SearchResult> reranked = rerankDocuments(query, ranked, speedProfile);
        return reranked.stream()
                .limit(safeLimit)
                .toList();
    }

    private List<SearchResult> rerankDocuments(String query, List<SearchResult> ranked, String speedProfile) {
        if (documentReranker == null) {
            return ranked;
        }
        String profile = speedProfile == null ? "BALANCED" : speedProfile.trim().toUpperCase(Locale.ROOT);
        if ("FAST".equals(profile)) {
            return documentReranker.skip(ranked, "fast_profile");
        }
        if (!"DEEP".equals(profile) && hasClearWinner(ranked)) {
            return documentReranker.skip(ranked, "clear_winner");
        }
        return documentReranker.rerank(query, ranked);
    }

    private boolean hasClearWinner(List<SearchResult> ranked) {
        if (ranked == null || ranked.size() < 2) {
            return true;
        }
        double first = ranked.get(0).score();
        double second = ranked.get(1).score();
        return first >= 0.75 || first - second >= 0.18;
    }

    public List<String> expandedQueries(String query) {
        String safeQuery = safeQuery(query);
        if (safeQuery.isBlank()) {
            return List.of();
        }
        String normalized = normalize(safeQuery);
        List<String> values = new ArrayList<>();
        values.add(safeQuery);
        if (normalized.contains("차별")) {
            values.addAll(List.of("차별 예방 개선", "임금 복리후생 교육훈련 고충처리", "기간제 단시간 파견 근로자"));
        }
        if (normalized.contains("기간제") || normalized.contains("단시간") || normalized.contains("파견")) {
            values.add("기간제 단시간 파견 근로자 차별");
        }
        if (normalized.contains("로그인")) {
            values.addAll(List.of("login auth authentication session token", "인증 세션 토큰"));
        }
        if (normalized.contains("인덱싱") || normalized.contains("색인")) {
            values.addAll(List.of("index indexing embedding chunk", "인덱싱 임베딩 청크 실패"));
        }
        if (normalized.contains("오류") || normalized.contains("실패")) {
            values.addAll(List.of("error exception failure failed", "오류 예외 실패 원인"));
        }
        if (normalized.contains("요약")) {
            values.add("핵심 요약 주요 내용");
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(12)
                .toList();
    }

    private double rerankBoost(String query, SearchResult result) {
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return 0;
        }

        String title = normalize(result.title());
        String uri = normalize(result.sourceUri());
        String content = normalize(result.content());
        double boost = 0;
        int matchedTerms = 0;
        for (String term : terms) {
            boolean matched = false;
            if (title.contains(term)) {
                boost += 0.28;
                matched = true;
            }
            if (uri.contains(term)) {
                boost += 0.12;
                matched = true;
            }
            if (content.contains(term)) {
                boost += 0.08;
                matched = true;
            }
            if (matched) {
                matchedTerms++;
            }
        }
        boost += Math.min(0.35, matchedTerms * 0.05);
        if (isSpreadsheetQuestion(query) && isSpreadsheet(result)) {
            boost += 0.35;
        }
        if (isOverviewQuestion(query) && isDocumentContext(result)) {
            String contextType = contextType(result);
            boost += ("document_summary".equals(contextType) || "source_summary".equals(contextType)) ? 0.42 : 0.20;
        }
        if (isStructureQuestion(query) && isDocumentContext(result)) {
            String contextType = contextType(result);
            boost += ("document_structure".equals(contextType) || "source_structure".equals(contextType)) ? 0.38 : 0.12;
        }
        if (!isOverviewQuestion(query) && !isStructureQuestion(query) && isDocumentContext(result)) {
            boost -= ("document_summary".equals(contextType(result)) || "source_summary".equals(contextType(result))) ? 0.06 : 0.14;
        }
        if (normalize(query).contains("차별") && title.contains("차별")) {
            boost += 0.2;
        }
        return boost;
    }

    private List<String> queryTerms(String query) {
        String normalized = normalize(query);
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !isStopWord(token)) {
                terms.add(token);
            }
        }
        if (normalized.contains("차별")) {
            terms.addAll(List.of("차별", "개선", "예방", "처우", "임금", "복리후생", "교육훈련", "고충"));
        }
        if (normalized.contains("근로자")) {
            terms.addAll(List.of("기간제", "단시간", "파견", "근로자"));
        }
        if (normalized.contains("로그인")) {
            terms.addAll(List.of("로그인", "인증", "세션", "토큰", "login", "auth"));
        }
        return terms.stream()
                .map(this::normalize)
                .filter(term -> term.length() >= 2 && !isStopWord(term))
                .distinct()
                .toList();
    }

    private boolean isSpreadsheetQuestion(String query) {
        String normalized = normalize(query);
        return normalized.contains("몇명")
                || normalized.contains("몇 명")
                || normalized.contains("총")
                || normalized.contains("건수")
                || normalized.contains("개수")
                || normalized.contains("count")
                || normalized.contains("total");
    }

    private boolean isSpreadsheet(SearchResult result) {
        String contentType = normalize(result.contentType());
        String title = normalize(result.title());
        return contentType.contains("spreadsheet")
                || contentType.contains("excel")
                || contentType.contains("csv")
                || title.endsWith("xlsx")
                || title.endsWith("xls")
                || title.endsWith("csv");
    }

    private boolean isOverviewQuestion(String query) {
        String normalized = normalize(query);
        return normalized.contains("summary")
                || normalized.contains("summarize")
                || normalized.contains("overview")
                || normalized.contains("outline")
                || normalized.contains("main")
                || normalized.contains("topic")
                || normalized.contains("요약")
                || normalized.contains("개요")
                || normalized.contains("정리")
                || normalized.contains("주요")
                || normalized.contains("핵심");
    }

    private boolean isStructureQuestion(String query) {
        String normalized = normalize(query);
        return normalized.contains("structure")
                || normalized.contains("section")
                || normalized.contains("page")
                || normalized.contains("slide")
                || normalized.contains("sheet")
                || normalized.contains("table")
                || normalized.contains("where")
                || normalized.contains("구조")
                || normalized.contains("목차")
                || normalized.contains("섹션")
                || normalized.contains("페이지")
                || normalized.contains("표")
                || normalized.contains("시트")
                || normalized.contains("어디");
    }

    private boolean isStopWord(String token) {
        return List.of("관련", "대해", "무엇", "뭐가", "어떤", "어디", "있어", "있나요", "되는거야", "되나요", "설명", "알려줘")
                .contains(token);
    }

    private void merge(Map<UUID, SearchResult> merged, SearchResult result) {
        SearchResult current = merged.get(result.chunkId());
        if (current == null || result.score() > current.score()) {
            merged.put(result.chunkId(), result);
        }
    }

    private List<Double> embeddingFor(String semanticQuery) {
        if (!embeddingCacheEnabled()) {
            return ollamaClient.embed(List.of(semanticQuery)).get(0);
        }
        String key = embeddingCacheKey(semanticQuery);
        long now = System.currentTimeMillis();
        CachedEmbedding cached = embeddingCache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.values();
        }
        List<Double> embedding = ollamaClient.embed(List.of(semanticQuery)).get(0);
        embeddingCache.put(key, new CachedEmbedding(embedding, now + embeddingCacheTtlMillis()));
        trimEmbeddingCache();
        return embedding;
    }

    private boolean embeddingCacheEnabled() {
        return properties != null && properties.getRag().getPipeline().isQueryEmbeddingCacheEnabled();
    }

    private long embeddingCacheTtlMillis() {
        int seconds = properties == null ? 600 : properties.getRag().getPipeline().getQueryEmbeddingCacheTtlSeconds();
        return Math.max(1, seconds) * 1000L;
    }

    private String embeddingCacheKey(String query) {
        String model = properties == null ? "" : properties.getOllama().getEmbeddingModel();
        return model + "::" + normalize(query);
    }

    private void trimEmbeddingCache() {
        int max = properties == null ? 1024 : Math.max(1, properties.getRag().getPipeline().getQueryEmbeddingCacheMaxEntries());
        if (embeddingCache.size() <= max) {
            return;
        }
        long now = System.currentTimeMillis();
        embeddingCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (embeddingCache.size() <= max) {
            return;
        }
        int remove = embeddingCache.size() - max;
        for (String key : new ArrayList<>(embeddingCache.keySet())) {
            embeddingCache.remove(key);
            remove--;
            if (remove <= 0) {
                break;
            }
        }
    }

    private SearchResult boost(SearchResult result, double value) {
        return new SearchResult(
                result.chunkId(),
                result.documentId(),
                result.title(),
                result.sourceUri(),
                result.sourceType(),
                result.contentType(),
                result.chunkIndex(),
                result.content(),
                result.metadata(),
                result.score() + value
        );
    }

    private boolean isDocumentContext(SearchResult result) {
        return "document_context".equals(stringMetadata(result, "kind"));
    }

    private String contextType(SearchResult result) {
        return stringMetadata(result, "contextType");
    }

    private String stringMetadata(SearchResult result, String key) {
        Object value = result.metadata() == null ? null : result.metadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safeQuery(String query) {
        return query == null ? "" : query.trim();
    }

    private record CachedEmbedding(List<Double> values, long expiresAtMillis) {
    }
}
