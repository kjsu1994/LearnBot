package com.learnbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagPipelineService {
    private static final Logger log = LoggerFactory.getLogger(RagPipelineService.class);
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final int MAX_REWRITE_QUERIES = 6;
    private static final int MAX_QUERY_CHARS = 180;

    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagPipelineService(OllamaClient ollamaClient, LearnBotProperties properties) {
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    public QueryPlan buildQueryPlan(String question, Domain domain, List<String> baselineQueries) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        addQuery(queries, question);
        addQueries(queries, baselineQueries);

        if (!pipeline().isRewriteEnabled()) {
            return new QueryPlan(domain, List.copyOf(queries), false, false, "rewrite disabled");
        }

        try {
            String response = ollamaClient.chat(
                    rewriteSystemPrompt(domain),
                    rewriteUserPrompt(question, domain, baselineQueries),
                    OllamaClient.ChatRole.AUXILIARY
            );
            List<String> rewritten = parseRewriteQueries(response);
            addQueries(queries, rewritten);
            boolean usedRewrite = !rewritten.isEmpty();
            return new QueryPlan(
                    domain,
                    List.copyOf(queries),
                    usedRewrite,
                    false,
                    usedRewrite ? "llm rewrite accepted" : "llm rewrite returned no usable queries"
            );
        } catch (RuntimeException ex) {
            log.info("RAG query rewrite skipped domain={} reason={} question={}",
                    domain, ex.getClass().getSimpleName(), abbreviate(question));
            return new QueryPlan(domain, List.copyOf(queries), false, true, "llm rewrite failed");
        }
    }

    public EvidenceAssessment assessDocuments(String question, List<SearchResult> results, int minEvidence, int iteration) {
        if (results == null || results.isEmpty()) {
            return new EvidenceAssessment(false, iteration, 0, 0, 0, List.of("no evidence"));
        }

        double topScore = results.get(0).score();
        int distinctSources = (int) results.stream().map(SearchResult::documentId).distinct().count();
        double coverage = coverage(question, results.stream()
                .limit(8)
                .map(result -> safe(result.title()) + " " + safe(result.sourceUri()) + " " + safe(result.content()))
                .toList());
        boolean enoughCount = results.size() >= minEvidence || topScore >= 0.65;
        boolean enoughScore = topScore >= pipeline().getMinTopScore() || results.size() >= minEvidence + 2;
        boolean enoughCoverage = coverage >= pipeline().getMinCoverage() || queryTerms(question).isEmpty();
        boolean sufficient = enoughCount && enoughScore && enoughCoverage;
        return new EvidenceAssessment(
                sufficient,
                iteration,
                topScore,
                distinctSources,
                coverage,
                reasons(sufficient, enoughCount, enoughScore, enoughCoverage)
        );
    }

    public EvidenceAssessment assessCode(String question, List<CodeSearchResult> results, int minEvidence, int iteration) {
        if (results == null || results.isEmpty()) {
            return new EvidenceAssessment(false, iteration, 0, 0, 0, List.of("no evidence"));
        }

        double topScore = results.get(0).score();
        int distinctSources = (int) results.stream().map(CodeSearchResult::filePath).distinct().count();
        double coverage = coverage(question, results.stream()
                .limit(10)
                .map(result -> safe(result.filePath()) + " "
                        + safe(result.symbolName()) + " "
                        + safe(result.className()) + " "
                        + safe(result.methodName()) + " "
                        + safe(result.content()))
                .toList());
        boolean hasStructuredEvidence = results.stream().anyMatch(this::isStructuredCodeEvidence);
        boolean enoughCount = results.size() >= minEvidence || (hasStructuredEvidence && topScore >= 0.55);
        boolean enoughScore = topScore >= pipeline().getMinTopScore() || hasStructuredEvidence;
        boolean enoughCoverage = coverage >= pipeline().getMinCoverage()
                || queryTerms(question).isEmpty()
                || (hasStructuredEvidence && topScore >= 0.55);
        boolean sufficient = enoughCount && enoughScore && enoughCoverage;
        return new EvidenceAssessment(
                sufficient,
                iteration,
                topScore,
                distinctSources,
                coverage,
                reasons(sufficient, enoughCount, enoughScore, enoughCoverage)
        );
    }

    public AnswerAssessment assessAnswer(String answer, int evidenceCount, boolean citationRequired) {
        return assessAnswer(answer, evidenceCount, citationRequired, null);
    }

    public AnswerAssessment assessAnswer(String answer, int evidenceCount, boolean citationRequired, String doneReason) {
        String trimmed = safe(answer).trim();
        if (!pipeline().isSelfCheckEnabled()) {
            return new AnswerAssessment(true, "self-check disabled");
        }
        if (trimmed.isBlank()) {
            return new AnswerAssessment(false, "blank answer");
        }
        if (trimmed.length() < 12) {
            return new AnswerAssessment(false, "answer too short");
        }
        if ("length".equalsIgnoreCase(safe(doneReason))) {
            return new AnswerAssessment(false, "model stopped before finishing");
        }
        if (looksIncompleteEnding(trimmed)) {
            return new AnswerAssessment(false, "answer appears incomplete");
        }
        List<Integer> citations = citations(trimmed);
        if (citationRequired && citations.isEmpty()) {
            return new AnswerAssessment(false, "missing citation");
        }
        for (Integer citation : citations) {
            if (citation < 1 || citation > evidenceCount) {
                return new AnswerAssessment(false, "citation out of range");
            }
        }
        return new AnswerAssessment(true, "grounding checks passed");
    }

    public int maxIterations() {
        return Math.max(1, Math.min(3, pipeline().getMaxIterations()));
    }

    public int documentSearchLimit(int requestedLimit) {
        return Math.max(1, Math.min(20, Math.max(requestedLimit, pipeline().getRerankTopN())));
    }

    public int codeSearchLimit(int requestedLimit) {
        return Math.max(1, Math.min(30, Math.max(requestedLimit, pipeline().getRerankTopN())));
    }

    public int documentContextLimit(int fallback) {
        return Math.max(1, Math.min(12, pipeline().getDocumentContextLimit() <= 0 ? fallback : pipeline().getDocumentContextLimit()));
    }

    public int codeContextLimit(int fallback) {
        return Math.max(1, Math.min(12, pipeline().getCodeContextLimit() <= 0 ? fallback : pipeline().getCodeContextLimit()));
    }

    private LearnBotProperties.Rag.Pipeline pipeline() {
        return properties.getRag().getPipeline();
    }

    private String rewriteSystemPrompt(Domain domain) {
        String domainHint = domain == Domain.CODE
                ? "source-code search over files, symbols, methods, UI events, and git commit-related questions"
                : "private document search over PDFs, spreadsheets, web pages, policies, tables, and exact quotes";
        return """
                You rewrite user questions into retrieval queries for a RAG system.
                Return strict JSON only. No Markdown.
                JSON schema: {"queries":["query 1","query 2"],"keywords":["term 1","term 2"],"reason":"short reason"}
                Keep queries short and concrete. Preserve Korean terms and add English technical synonyms only when useful.
                Do not answer the question.
                Domain: """ + domainHint;
    }

    private String rewriteUserPrompt(String question, Domain domain, List<String> baselineQueries) {
        return "Domain: " + domain + "\n"
                + "Original question:\n" + safe(question) + "\n\n"
                + "Existing deterministic queries:\n" + String.join("\n", baselineQueries == null ? List.of() : baselineQueries) + "\n\n"
                + "Return JSON only.";
    }

    private List<String> parseRewriteQueries(String response) {
        String json = extractJsonObject(response);
        if (json.isBlank()) {
            throw new IllegalArgumentException("Query rewrite response did not contain JSON");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            LinkedHashSet<String> queries = new LinkedHashSet<>();
            addParsedStrings(queries, parsed.get("queries"));
            addParsedStrings(queries, parsed.get("keywords"));
            return queries.stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(MAX_REWRITE_QUERIES)
                    .toList();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid query rewrite JSON", ex);
        }
    }

    private void addParsedStrings(Set<String> output, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addQuery(output, String.valueOf(item));
            }
            return;
        }
        if (value instanceof String text) {
            addQuery(output, text);
        }
    }

    private String extractJsonObject(String response) {
        String text = safe(response).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    private void addQueries(Set<String> queries, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addQuery(queries, value);
        }
    }

    private void addQuery(Set<String> queries, String value) {
        String query = safe(value).replaceAll("\\s+", " ").trim();
        if (query.isBlank()) {
            return;
        }
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS).trim();
        }
        queries.add(query);
    }

    private double coverage(String question, List<String> texts) {
        List<String> terms = queryTerms(question);
        if (terms.isEmpty()) {
            return 1.0;
        }
        String haystack = normalize(String.join(" ", texts));
        long matched = terms.stream().filter(haystack::contains).count();
        return (double) matched / (double) terms.size();
    }

    private List<String> queryTerms(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !isStopWord(token)) {
                terms.add(token);
            }
        }
        return terms.stream().distinct().limit(12).toList();
    }

    private boolean isStopWord(String token) {
        return List.of(
                "the", "and", "for", "with", "what", "where", "when", "how",
                "about", "please", "show", "tell", "this", "that"
        ).contains(token);
    }

    private String normalize(String value) {
        return safe(value)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> reasons(boolean sufficient, boolean enoughCount, boolean enoughScore, boolean enoughCoverage) {
        if (sufficient) {
            return List.of("evidence sufficient");
        }
        List<String> reasons = new ArrayList<>();
        if (!enoughCount) {
            reasons.add("not enough evidence");
        }
        if (!enoughScore) {
            reasons.add("top score below threshold");
        }
        if (!enoughCoverage) {
            reasons.add("query coverage below threshold");
        }
        return reasons;
    }

    private List<Integer> citations(String answer) {
        List<Integer> citations = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            try {
                citations.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Regex guarantees digits, but keep this path harmless.
            }
        }
        return citations;
    }

    private boolean isStructuredCodeEvidence(CodeSearchResult result) {
        return result != null && ("class".equals(result.chunkType())
                || "method".equals(result.chunkType())
                || "event_handler".equals(result.chunkType())
                || "xaml_event".equals(result.chunkType())
                || "xaml_view".equals(result.chunkType())
                || notBlank(result.methodName())
                || notBlank(result.className())
                || notBlank(result.symbolName()));
    }

    private boolean looksIncompleteEnding(String answer) {
        String trimmed = stripTrailingMarkdownNoise(answer);
        if (trimmed.isBlank()) {
            return true;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (isAcceptableTerminal(last)) {
            return false;
        }
        String lastLine = trimmed.lines()
                .reduce((first, second) -> second)
                .orElse(trimmed)
                .trim();
        String normalized = lastLine.replaceAll("\\s+", " ");
        return normalized.length() < 24 || endsWithDanglingWord(normalized);
    }

    private String stripTrailingMarkdownNoise(String answer) {
        String trimmed = safe(answer).trim();
        while (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    private boolean isAcceptableTerminal(char value) {
        return ".?!。！？)]}`|".indexOf(value) >= 0
                || "다요음함됨임".indexOf(value) >= 0;
    }

    private boolean endsWithDanglingWord(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.endsWith("라는")
                || normalized.endsWith("이라는")
                || normalized.endsWith("위한")
                || normalized.endsWith("통해")
                || normalized.endsWith("및")
                || normalized.endsWith("또는")
                || normalized.endsWith("그리고")
                || normalized.endsWith("정");
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String abbreviate(String value) {
        String compact = safe(value).replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public enum Domain {
        DOCUMENT,
        CODE
    }

    public record QueryPlan(
            Domain domain,
            List<String> queries,
            boolean rewriteUsed,
            boolean rewriteFailed,
            String reason
    ) {
    }

    public record EvidenceAssessment(
            boolean sufficient,
            int iteration,
            double topScore,
            int distinctSources,
            double coverage,
            List<String> reasons
    ) {
    }

    public record AnswerAssessment(boolean acceptable, String reason) {
    }
}
