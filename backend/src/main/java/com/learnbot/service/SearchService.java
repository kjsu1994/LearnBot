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
    private final DocumentDomainProfileService domainProfileService;
    private final Map<String, CachedEmbedding> embeddingCache = new ConcurrentHashMap<>();

    public SearchService(DocumentRepository repository, OllamaClient ollamaClient) {
        this(repository, ollamaClient, null, null, new DocumentDomainProfileService());
    }

    public SearchService(DocumentRepository repository, OllamaClient ollamaClient, DocumentReranker documentReranker, LearnBotProperties properties) {
        this(repository, ollamaClient, documentReranker, properties, new DocumentDomainProfileService());
    }

    @Autowired
    public SearchService(
            DocumentRepository repository,
            OllamaClient ollamaClient,
            DocumentReranker documentReranker,
            LearnBotProperties properties,
            DocumentDomainProfileService domainProfileService
    ) {
        this.repository = repository;
        this.ollamaClient = ollamaClient;
        this.documentReranker = documentReranker;
        this.properties = properties;
        this.domainProfileService = domainProfileService == null ? new DocumentDomainProfileService() : domainProfileService;
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit) {
        return search(query, filter, limit, null, null);
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit, List<java.util.UUID> spaceIds, java.util.UUID selectedSpaceId) {
        return search(query, filter, limit, spaceIds, selectedSpaceId, "BALANCED");
    }

    public List<SearchResult> search(String query, SearchFilter filter, int limit, List<java.util.UUID> spaceIds, java.util.UUID selectedSpaceId, String speedProfile) {
        return searchDetailed(query, filter, limit, spaceIds, selectedSpaceId, speedProfile).results();
    }

    public SearchResponse searchDetailed(String query, SearchFilter filter, int limit, List<java.util.UUID> spaceIds, java.util.UUID selectedSpaceId, String speedProfile) {
        long started = System.nanoTime();
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<java.util.UUID> safeSpaceIds = spaceIds == null || spaceIds.isEmpty()
                ? List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID)
                : spaceIds;
        int candidateLimit = Math.min(60, Math.max(safeLimit * 4, 20));
        Map<UUID, SearchResult> merged = new LinkedHashMap<>();
        List<String> expandedQueries = expandedQueries(query);
        long embeddingMs = 0;
        long vectorSearchMs = 0;
        long keywordSearchMs = 0;
        long rerankMs;
        boolean embeddingCacheHit = false;

        try {
            String semanticQuery = String.join(" ", expandedQueries);
            long embeddingStarted = System.nanoTime();
            CachedEmbeddingResult embeddingResult = embeddingFor(semanticQuery);
            embeddingMs = elapsedMs(embeddingStarted);
            embeddingCacheHit = embeddingResult.cacheHit();
            long vectorStarted = System.nanoTime();
            for (SearchResult result : repository.search(semanticQuery, embeddingResult.values(), filter, candidateLimit, safeSpaceIds, selectedSpaceId)) {
                merge(merged, result);
            }
            vectorSearchMs = elapsedMs(vectorStarted);
        } catch (RuntimeException ex) {
            // Keyword search below remains available when embeddings are temporarily unavailable.
        }

        long keywordStarted = System.nanoTime();
        for (String searchQuery : expandedQueries) {
            int keywordLimit = searchQuery.equalsIgnoreCase(safeQuery(query)) ? candidateLimit : Math.max(8, candidateLimit / 2);
            for (SearchResult result : repository.keywordSearch(searchQuery, filter, keywordLimit, safeSpaceIds, selectedSpaceId)) {
                merge(merged, searchQuery.equalsIgnoreCase(safeQuery(query)) ? result : boost(result, 0.05));
            }
        }
        keywordSearchMs = elapsedMs(keywordStarted);

        List<SearchResult> ranked = merged.values().stream()
                .map(result -> boost(result, rerankBoost(query, result)))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .toList();
        long rerankStarted = System.nanoTime();
        List<SearchResult> reranked = rerankDocuments(query, ranked, speedProfile);
        rerankMs = elapsedMs(rerankStarted);
        List<SearchResult> results = reranked.stream()
                .limit(safeLimit)
                .toList();
        return new SearchResponse(
                results,
                new SearchTiming(
                        embeddingMs,
                        vectorSearchMs,
                        keywordSearchMs,
                        rerankMs,
                        elapsedMs(started),
                        embeddingCacheHit,
                        expandedQueries.size()
                )
        );
    }

    private List<SearchResult> rerankDocuments(String query, List<SearchResult> ranked, String speedProfile) {
        if (documentReranker == null) {
            return ranked;
        }
        String profile = speedProfile == null ? "BALANCED" : speedProfile.trim().toUpperCase(Locale.ROOT);
        if ("FAST".equals(profile)) {
            return documentReranker.skip(ranked, "fast_profile");
        }
        if (!"DEEP".equals(profile) && !isOverviewQuestion(query) && !isStructureQuestion(query) && hasClearWinner(ranked)) {
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
        if (System.nanoTime() >= 0) {
            return cleanExpandedQueries(query);
        }
        String safeQuery = safeQuery(query);
        if (safeQuery.isBlank()) {
            return List.of();
        }
        String normalized = normalize(safeQuery);
        List<String> values = new ArrayList<>();
        values.add(safeQuery);
        if (containsAny(normalized, "차별", "예방", "개선")) {
            values.addAll(List.of(
                    "차별 예방 개선",
                    "임금 복리후생 교육훈련 고충처리 차별 예방"
            ));
        }
        if (containsAny(normalized, "요약", "개요", "정리", "전체", "핵심", "summary", "overview")) {
            values.addAll(List.of("문서 요약 주요 내용", "전체 구조 핵심 근거", "document summary overview main topics"));
        }
        if (containsAny(normalized, "구조", "목차", "섹션", "페이지", "조항", "위치", "어디", "structure", "section", "page", "where")) {
            values.addAll(List.of("문서 구조 목차 섹션 페이지 조항", "document structure section page heading"));
        }
        if (containsAny(normalized, "표", "테이블", "시트", "행", "열", "건수", "개수", "합계", "table", "sheet", "row", "count", "total")) {
            values.addAll(List.of("표 테이블 시트 행 열 건수 합계", "table sheet row column count total"));
        }
        if (containsAny(normalized, "원문", "인용", "근거", "조항", "quote", "citation", "evidence")) {
            values.addAll(List.of("원문 인용 근거 조항", "quote citation evidence clause"));
        }
        if (containsAny(normalized, "로그인", "인증", "세션", "토큰", "login", "auth", "session", "token")) {
            values.addAll(List.of("login auth authentication session token", "로그인 인증 세션 토큰"));
        }
        if (containsAny(normalized, "인덱싱", "임베딩", "청크", "검색", "index", "embedding", "chunk", "search")) {
            values.addAll(List.of("index indexing embedding chunk search", "인덱싱 임베딩 청크 검색"));
        }
        if (containsAny(normalized, "오류", "실패", "예외", "에러", "error", "exception", "failure", "failed")) {
            values.addAll(List.of("error exception failure failed", "오류 예외 실패 원인"));
        }
        try {
            values.addAll(domainProfileService.expandedQueries(safeQuery));
        } catch (RuntimeException ignored) {
            // Keep deterministic base expansions when profile lookup fails.
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(12)
                .toList();
    }

    private List<String> cleanExpandedQueries(String query) {
        String safeQuery = safeQuery(query);
        if (safeQuery.isBlank()) {
            return List.of();
        }
        String normalized = normalize(safeQuery);
        List<String> values = new ArrayList<>();
        values.add(safeQuery);
        if (containsAny(normalized, "차별", "예방", "개선")) {
            values.addAll(List.of(
                    "차별 예방 개선",
                    "임금 복리후생 교육훈련 고충처리 차별 예방"
            ));
        }
        if (containsAny(normalized, "요약", "개요", "정리", "전체", "핵심", "summary", "overview")) {
            values.addAll(List.of("문서 요약 주요 내용", "전체 구조 핵심 근거", "document summary overview main topics"));
        }
        if (containsAny(normalized, "구조", "목차", "섹션", "페이지", "조항", "위치", "어디", "structure", "section", "page", "where")) {
            values.addAll(List.of("문서 구조 목차 섹션 페이지 조항", "document structure section page heading"));
        }
        if (containsAny(normalized, "표", "테이블", "시트", "행", "열", "건수", "개수", "합계", "table", "sheet", "row", "count", "total")) {
            values.addAll(List.of("표 테이블 시트 행 열 건수 합계", "table sheet row column count total"));
        }
        if (containsAny(normalized, "원문", "인용", "근거", "조항", "quote", "citation", "evidence")) {
            values.addAll(List.of("원문 인용 근거 조항", "quote citation evidence clause"));
        }
        try {
            values.addAll(domainProfileService.expandedQueries(safeQuery));
        } catch (RuntimeException ignored) {
            // Keep deterministic base expansions when profile lookup fails.
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
        String section = normalize(stringMetadata(result, "sectionTitle"));
        String heading = normalize(stringMetadata(result, "headingPath"));
        String clause = normalize(stringMetadata(result, "clauseNumber"));
        String page = normalize(stringMetadata(result, "pageNumber"));
        String table = normalize(stringMetadata(result, "tableId"));
        String documentType = normalize(stringMetadata(result, "documentType"));
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
            if (!section.isBlank() && section.contains(term)) {
                boost += 0.20;
                matched = true;
            }
            if (!heading.isBlank() && heading.contains(term)) {
                boost += 0.16;
                matched = true;
            }
            if (!clause.isBlank() && clause.contains(term)) {
                boost += 0.22;
                matched = true;
            }
            if (!page.isBlank() && page.contains(term)) {
                boost += 0.08;
                matched = true;
            }
            if (!table.isBlank() && table.contains(term)) {
                boost += 0.14;
                matched = true;
            }
            if (!documentType.isBlank() && documentType.contains(term)) {
                boost += 0.08;
                matched = true;
            }
            if (matched) {
                matchedTerms++;
            }
        }
        boost += Math.min(0.35, matchedTerms * 0.05);
        if (isClauseQuestion(query) && (!clause.isBlank() || !section.isBlank() || !heading.isBlank())) {
            boost += 0.20;
        }
        if (isLocationQuestion(query) && (!page.isBlank() || !clause.isBlank() || !section.isBlank())) {
            boost += 0.18;
        }
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
        if (containsAny(normalized, "요약", "개요", "정리", "핵심")) {
            terms.addAll(List.of("요약", "개요", "핵심", "구조", "근거"));
        }
        if (containsAny(normalized, "구조", "목차", "섹션", "페이지", "조항")) {
            terms.addAll(List.of("구조", "목차", "섹션", "페이지", "조항", "heading", "section"));
        }
        if (containsAny(normalized, "로그인", "인증", "세션", "토큰")) {
            terms.addAll(List.of("로그인", "인증", "세션", "토큰", "login", "auth"));
        }
        return terms.stream()
                .map(this::normalize)
                .filter(term -> term.length() >= 2 && !isStopWord(term))
                .distinct()
                .toList();
    }
    private boolean isClauseQuestion(String query) {
        String normalized = normalize(query);
        return containsAny(normalized,
                "clause", "article", "criteria", "condition", "exception", "limit", "scope",
                "조항", "규정", "기준", "조건", "예외", "제한", "범위", "적용");
    }

    private boolean isLocationQuestion(String query) {
        String normalized = normalize(query);
        return containsAny(normalized,
                "where", "location", "page", "section",
                "위치", "어디", "몇 조", "몇조", "몇 항", "몇항", "페이지", "섹션");
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
        return containsAny(normalized,
                "summary", "summarize", "overview", "outline", "main", "topic",
                "요약", "개요", "정리", "전체", "핵심", "주요", "무엇", "어떤 내용");
    }
    private boolean isStructureQuestion(String query) {
        String normalized = normalize(query);
        return containsAny(normalized,
                "structure", "section", "page", "slide", "sheet", "table", "where",
                "구조", "목차", "섹션", "페이지", "슬라이드", "시트", "표", "테이블", "조항", "위치", "어디");
    }
    private boolean isStopWord(String token) {
        return List.of(
                "관련", "대해", "무엇", "뭐", "어떤", "어디", "있는", "없는", "설명", "알려줘", "보여줘",
                "the", "and", "for", "with", "what", "where", "when", "how", "about", "please", "show", "tell"
        ).contains(token);
    }

    private boolean containsAny(String value, String... needles) {
        String safeValue = value == null ? "" : value;
        for (String needle : needles) {
            if (safeValue.contains(needle)) {
                return true;
            }
        }
        return false;
    }
    private void merge(Map<UUID, SearchResult> merged, SearchResult result) {
        SearchResult current = merged.get(result.chunkId());
        if (current == null || result.score() > current.score()) {
            merged.put(result.chunkId(), result);
        }
    }

    private CachedEmbeddingResult embeddingFor(String semanticQuery) {
        if (!embeddingCacheEnabled()) {
            return new CachedEmbeddingResult(ollamaClient.embed(List.of(semanticQuery)).get(0), false);
        }
        String key = embeddingCacheKey(semanticQuery);
        long now = System.currentTimeMillis();
        CachedEmbedding cached = embeddingCache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return new CachedEmbeddingResult(cached.values(), true);
        }
        List<Double> embedding = ollamaClient.embed(List.of(semanticQuery)).get(0);
        embeddingCache.put(key, new CachedEmbedding(embedding, now + embeddingCacheTtlMillis()));
        trimEmbeddingCache();
        return new CachedEmbeddingResult(embedding, false);
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

    private record CachedEmbeddingResult(List<Double> values, boolean cacheHit) {
    }

    public record SearchResponse(List<SearchResult> results, SearchTiming timing) {
    }

    public record SearchTiming(
            long embeddingMs,
            long vectorSearchMs,
            long keywordSearchMs,
            long rerankMs,
            long totalMs,
            boolean embeddingCacheHit,
            int expandedQueryCount
    ) {
        static SearchTiming empty() {
            return new SearchTiming(0, 0, 0, 0, 0, false, 0);
        }
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
