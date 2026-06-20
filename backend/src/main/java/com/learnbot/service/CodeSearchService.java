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
    private static final Map<String, List<String>> QUERY_ALIASES = Map.ofEntries(
            Map.entry("로그인", List.of("login", "signin", "sign in", "auth", "authentication", "session", "token", "credential")),
            Map.entry("login", List.of("로그인", "signin", "sign in", "auth", "authentication", "session", "token", "credential")),
            Map.entry("인증", List.of("auth", "authentication", "authorization", "principal", "session", "token", "credential")),
            Map.entry("auth", List.of("인증", "authentication", "authorization", "principal", "session", "token", "credential")),
            Map.entry("사용자", List.of("user", "member", "account", "principal")),
            Map.entry("권한", List.of("role", "permission", "authority", "authorization")),
            Map.entry("세션", List.of("session", "cookie", "token", "auth")),
            Map.entry("토큰", List.of("token", "jwt", "bearer", "credential")),
            Map.entry("인덱싱", List.of("index", "indexing", "repository", "job", "chunk", "embedding")),
            Map.entry("index", List.of("인덱싱", "indexing", "repository", "job", "chunk", "embedding")),
            Map.entry("실패", List.of("fail", "failed", "failure", "error", "exception")),
            Map.entry("error", List.of("실패", "fail", "failed", "failure", "exception")),
            Map.entry("문서", List.of("document", "docs", "file", "source")),
            Map.entry("저장소", List.of("repository", "repo", "git")),
            Map.entry("검색", List.of("search", "query", "rag", "retrieval")),
            Map.entry("호출", List.of("flow", "call", "handler", "service")),
            Map.entry("화면", List.of("ui", "view", "page", "component", "controller")),
            Map.entry("업로드", List.of("upload", "ingest", "file")),
            Map.entry("관리자", List.of("admin", "audit", "user", "space")),
            Map.entry("전체", List.of("application", "controller", "service", "repository", "config", "security", "frontend", "backend", "rag", "document", "index")),
            Map.entry("뭐", List.of("application", "controller", "service", "repository", "config", "security", "frontend", "backend", "rag", "document", "index")),
            Map.entry("코드", List.of("application", "controller", "service", "repository", "config", "security", "frontend", "backend", "rag", "document", "index"))
    );

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
        return search(repositoryId, query, limit, spaceIds, selectedSpaceId, GraphSearchIntent.DEFAULT);
    }

    public List<CodeSearchResult> search(UUID repositoryId, String query, int limit, List<UUID> spaceIds,
                                         UUID selectedSpaceId, GraphSearchIntent graphIntent) {
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 30));
        int candidateLimit = Math.min(80, Math.max(safeLimit * 4, 24));
        List<UUID> safeSpaceIds = spaceIds == null || spaceIds.isEmpty()
                ? java.util.List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID)
                : spaceIds;
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        List<String> expandedQueries = expandedQueries(safeQuery);

        for (String searchQuery : expandedQueries) {
            for (CodeSearchResult result : repository.keywordSearch(repositoryId, searchQuery, candidateLimit, safeSpaceIds, selectedSpaceId)) {
                merge(merged, searchQuery.equalsIgnoreCase(safeQuery) ? result : boost(result, 0.06));
            }
        }
        for (String identifier : identifiersFrom(String.join(" ", expandedQueries))) {
            for (CodeSearchResult result : repository.keywordSearch(repositoryId, identifier, Math.max(8, candidateLimit / 2), safeSpaceIds, selectedSpaceId)) {
                merge(merged, boost(result, 0.18));
            }
        }

        try {
            String semanticQuery = String.join(" ", expandedQueries);
            List<Double> embedding = ollamaClient.embed(List.of(semanticQuery)).get(0);
            for (CodeSearchResult result : repository.search(repositoryId, semanticQuery, embedding, candidateLimit, safeSpaceIds, selectedSpaceId)) {
                merge(merged, result);
            }
        } catch (RuntimeException ignored) {
            // Keyword and exact identifier search remain available when embeddings are temporarily unavailable.
        }

        List<CodeSearchResult> ranked = merged.values().stream()
                .map(result -> boost(result, rerankBoost(safeQuery, result)))
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed())
                .limit(safeLimit)
                .toList();
        List<CodeSearchResult> expanded = expandRelated(repositoryId, ranked, safeLimit);
        expanded = expandGraph(repositoryId, safeQuery, expanded, safeLimit, resolveIntent(safeQuery, graphIntent));
        return expanded.stream()
                .map(result -> boost(result, rerankBoost(safeQuery, result)))
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed())
                .limit(safeLimit)
                .toList();
    }

    public List<String> expandedQueries(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String safeQuery = query.trim();
        String lower = safeQuery.toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        values.add(safeQuery);
        QUERY_ALIASES.forEach((trigger, aliases) -> {
            if (lower.contains(trigger.toLowerCase(Locale.ROOT))) {
                values.addAll(aliases);
            }
        });
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(18)
                .toList();
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

    private List<CodeSearchResult> expandGraph(UUID repositoryId, String query, List<CodeSearchResult> ranked,
                                               int limit, GraphSearchIntent intent) {
        if (!properties.getCode().getGraph().isEnabled() || ranked == null || ranked.isEmpty()) {
            return ranked;
        }
        try {
            Map<UUID, CodeSearchResult> expanded = new LinkedHashMap<>();
            for (CodeSearchResult result : ranked) {
                merge(expanded, result);
            }
            List<UUID> seeds = ranked.stream()
                    .limit(Math.min(ranked.size(), 5))
                    .map(CodeSearchResult::chunkId)
                    .toList();
            for (CodeSearchResult related : repository.graphRelatedChunks(
                    repositoryId,
                    seeds,
                    graphEdgeTypes(query, intent),
                    graphMaxHop(intent),
                    graphDirection(intent),
                    Math.max(limit, properties.getCode().getGraph().getMaxExpandedResults())
            )) {
                merge(expanded, boost(related, graphBoost(query, related)));
            }
            return expanded.values().stream()
                    .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed())
                    .limit(Math.max(limit, properties.getCode().getGraph().getMaxExpandedResults()))
                    .toList();
        } catch (RuntimeException ignored) {
            return ranked;
        }
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

    public List<String> identifiersFrom(String query) {
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

    private double rerankBoost(String query, CodeSearchResult result) {
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return 0;
        }

        String path = normalizeCodeText(result.filePath());
        String symbolText = normalizeCodeText(String.join(" ",
                safe(result.symbolName()),
                safe(result.className()),
                safe(result.methodName()),
                safe(result.controlName()),
                safe(result.eventName())
        ));
        String content = normalizeCodeText(result.content());
        double boost = 0;
        int matchedTerms = 0;
        for (String term : terms) {
            boolean matched = false;
            if (path.contains(term)) {
                boost += 0.35;
                matched = true;
            }
            if (symbolText.contains(term)) {
                boost += 0.32;
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
        if (isStructured(result.chunkType())) {
            boost += 0.08;
        }
        if (isLoginQuestion(query) && (path.contains("auth") || path.contains("login") || symbolText.contains("login"))) {
            boost += 0.35;
        }
        if (isLoginQuestion(query) && path.contains("git") && !path.contains("auth") && !path.contains("login")) {
            boost -= 0.6;
        }
        if (isOverviewQuestion(query) && isProjectContext(result.chunkType())) {
            boost += "project_structure".equals(result.chunkType()) || "repository_summary".equals(result.chunkType()) ? 0.70 : 0.38;
        }
        if (!isOverviewQuestion(query) && isProjectContext(result.chunkType())) {
            boost -= "file_summary".equals(result.chunkType()) ? 0.05 : 0.18;
        }
        return boost;
    }

    private List<String> queryTerms(String query) {
        String normalized = normalizeCodeText(query);
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !isQuestionStopWord(token)) {
                terms.add(token);
            }
        }
        if (isLoginQuestion(query)) {
            terms.addAll(List.of("login", "signin", "auth", "authentication", "session", "token", "로그인", "인증"));
        }
        if (normalized.contains("인덱") || normalized.contains("index")) {
            terms.addAll(List.of("index", "indexing", "repository", "chunk", "embedding", "인덱싱"));
        }
        if (normalized.contains("오류") || normalized.contains("실패") || normalized.contains("error")) {
            terms.addAll(List.of("error", "exception", "failed", "failure", "오류", "실패"));
        }
        if (normalized.contains("관리자") || normalized.contains("admin")) {
            terms.addAll(List.of("admin", "role", "authority", "audit", "관리자"));
        }
        return terms.stream()
                .map(this::normalizeCodeText)
                .filter(term -> term.length() >= 2 && !isQuestionStopWord(term))
                .distinct()
                .toList();
    }

    private boolean isLoginQuestion(String query) {
        String normalized = normalizeCodeText(query);
        return normalized.contains("로그인") || normalized.contains("login") || normalized.contains("signin");
    }

    private boolean isOverviewQuestion(String query) {
        String normalized = normalizeCodeText(query);
        return normalized.contains("overview")
                || normalized.contains("architecture")
                || normalized.contains("structure")
                || normalized.contains("project")
                || normalized.contains("module")
                || normalized.contains("component")
                || normalized.contains("\uD504\uB85C\uC81D\uD2B8")
                || normalized.contains("\uAD6C\uC870")
                || normalized.contains("\uC544\uD0A4\uD14D\uCC98")
                || normalized.contains("\uBAA8\uB4C8")
                || normalized.contains("\uAD6C\uC131");
    }

    private List<String> graphEdgeTypes(String query, GraphSearchIntent intent) {
        if (intent == GraphSearchIntent.FLOW) {
            return List.of("EXPOSES_ENDPOINT", "CALLS", "INJECTS", "RETURNS", "HANDLES_EVENT");
        }
        if (intent == GraphSearchIntent.IMPACT) {
            return List.of("CALLS", "OVERRIDES", "IMPLEMENTS", "EXTENDS", "INJECTS", "READS_FIELD", "WRITES_FIELD", "USES_ENTITY");
        }
        if (intent == GraphSearchIntent.UI_EVENT) {
            return List.of("HANDLES_EVENT", "BINDS_TO", "EXPOSES_ENDPOINT", "CALLS", "READS_FIELD", "WRITES_FIELD");
        }
        if (intent == GraphSearchIntent.OVERVIEW) {
            return List.of("CONTAINS", "DEFINES", "EXTENDS", "IMPLEMENTS", "INJECTS", "ANNOTATED_BY", "MAPS_TO_TABLE", "EXPOSES_ENDPOINT");
        }
        String normalized = normalizeCodeText(query);
        if (normalized.contains("flow") || normalized.contains("impact") || normalized.contains("call")
                || normalized.contains("흐름") || normalized.contains("호출")) {
            return List.of("CALLS", "REFERENCES", "HANDLES_EVENT", "BINDS_TO");
        }
        if (isOverviewQuestion(query)) {
            return List.of("CONTAINS", "DEFINES", "REFERENCES", "DEPENDS_ON", "RELATED_TO");
        }
        return List.of("DEFINES", "CONTAINS", "CALLS", "REFERENCES", "HANDLES_EVENT", "BINDS_TO");
    }

    private int graphMaxHop(GraphSearchIntent intent) {
        int configured = Math.max(1, Math.min(properties.getCode().getGraph().getMaxHop(), 4));
        return switch (intent) {
            case LOCATE, EXPLAIN -> 1;
            default -> configured;
        };
    }

    private String graphDirection(GraphSearchIntent intent) {
        return switch (intent) {
            case FLOW -> "FORWARD";
            case IMPACT -> "REVERSE";
            default -> "BOTH";
        };
    }

    private GraphSearchIntent resolveIntent(String query, GraphSearchIntent requested) {
        if (requested != null && requested != GraphSearchIntent.DEFAULT) {
            return requested;
        }
        String normalized = normalizeCodeText(query);
        if (normalized.contains("impact") || normalized.contains("영향")) return GraphSearchIntent.IMPACT;
        if (normalized.contains("flow") || normalized.contains("call") || normalized.contains("호출")) return GraphSearchIntent.FLOW;
        if (normalized.contains("event") || normalized.contains("xaml") || normalized.contains("이벤트")) return GraphSearchIntent.UI_EVENT;
        if (isOverviewQuestion(query)) return GraphSearchIntent.OVERVIEW;
        return GraphSearchIntent.LOCATE;
    }

    private double graphBoost(String query, CodeSearchResult result) {
        Object edgeType = result.metadata() == null ? null : result.metadata().get("graphEdgeType");
        String type = edgeType == null ? "" : String.valueOf(edgeType);
        if ("CALLS".equals(type) || "HANDLES_EVENT".equals(type)) {
            return 0.16;
        }
        if ("DEFINES".equals(type) || "CONTAINS".equals(type)) {
            return isOverviewQuestion(query) ? 0.14 : 0.08;
        }
        return 0.06;
    }

    private boolean isStructured(String chunkType) {
        return "class".equals(chunkType)
                || "method".equals(chunkType)
                || "event_handler".equals(chunkType)
                || "xaml_event".equals(chunkType)
                || "xaml_view".equals(chunkType)
                || isProjectContext(chunkType);
    }

    private boolean isProjectContext(String chunkType) {
        return "project_structure".equals(chunkType)
                || "repository_summary".equals(chunkType)
                || "directory_summary".equals(chunkType)
                || "file_summary".equals(chunkType);
    }

    private boolean isQuestionStopWord(String term) {
        return List.of("관련", "파일", "어디", "있어", "있나요", "어떻게", "동작", "설명", "위치", "찾아", "찾기", "코드")
                .contains(term);
    }

    private String normalizeCodeText(String value) {
        return value == null
                ? ""
                : value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
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
