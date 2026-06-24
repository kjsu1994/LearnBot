package com.learnbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeConversationAnchor;
import com.learnbot.dto.ConversationIntent;
import com.learnbot.dto.DocumentConversationAnchor;
import com.learnbot.dto.PreviousAnswerItem;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationDetail;
import com.learnbot.dto.RagConversationSummary;
import com.learnbot.dto.RagConversationTurn;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.repository.RagConversationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagConversationService {
    public static final String DOCUMENT = "DOCUMENT";
    public static final String CODE = "CODE";

    private static final int RECENT_TURN_LIMIT = 5;
    private static final int MAX_REWRITE_CHARS = 520;
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern OUTLINE_PATTERN = Pattern.compile("^\\s*(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)(.+?)\\s*$");

    private final RagConversationRepository repository;
    private final ObjectMapper objectMapper;

    public RagConversationService(RagConversationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<RagConversationSummary> list(AppUser user, UUID spaceId, String domain) {
        return repository.list(user.id(), spaceId, normalizeDomain(domain), 40);
    }

    public RagConversationDetail detail(AppUser user, UUID conversationId) {
        return repository.findDetail(user.id(), conversationId)
                .orElseThrow(() -> new IllegalArgumentException("RAG conversation was not found."));
    }

    public void delete(AppUser user, UUID conversationId) {
        repository.softDelete(user.id(), conversationId);
    }

    public RagConversationContext prepare(
            AppUser user,
            UUID spaceId,
            String domain,
            UUID repositoryId,
            UUID conversationId,
            String question,
            boolean conversational
    ) {
        if (!conversational && conversationId == null) {
            return new RagConversationContext(null, clean(question), List.of());
        }
        String normalizedDomain = normalizeDomain(domain);
        RagConversationSummary conversation = conversationId == null
                ? repository.create(user.id(), spaceId, normalizedDomain, repositoryId, title(question))
                : repository.findSummary(user.id(), conversationId)
                .orElseThrow(() -> new IllegalArgumentException("RAG conversation was not found."));
        validateConversationScope(conversation, spaceId, normalizedDomain, repositoryId);
        List<RagConversationTurnContext> recentTurns = repository.recentTurnContexts(conversation.id(), RECENT_TURN_LIMIT);
        boolean hasRecentTurns = recentTurns != null && !recentTurns.isEmpty();
        ConversationIntent intent = classifyConversationIntent(question, hasRecentTurns);
        List<CodeConversationAnchor> codeAnchors = CODE.equals(normalizedDomain) && hasRecentTurns
                ? codeAnchors(recentTurns)
                : List.of();
        boolean referenceFollowup = hasRecentTurns && (
                looksContextual(normalizedDomain, question)
                        || looksReferenceFollowup(question)
                        || (CODE.equals(normalizedDomain) && !codeAnchors.isEmpty() && looksShortCodeFollowup(question))
        );
        boolean contextual = intent != ConversationIntent.NONE || referenceFollowup;
        ConversationIntent effectiveIntent = intent == ConversationIntent.NONE && contextual
                ? ConversationIntent.REFERENCE_FOLLOWUP
                : intent;
        List<PreviousAnswerItem> previousItems = effectiveIntent == ConversationIntent.PREVIOUS_ANSWER_EXPANSION
                ? previousAnswerItems(recentTurns.get(0))
                : List.of();
        List<UUID> requiredChunkIds = requiredChunkIds(previousItems);
        if (CODE.equals(normalizedDomain)) {
            List<CodeConversationAnchor> anchors = contextual ? codeAnchors : List.of();
            String rewritten = effectiveIntent == ConversationIntent.PREVIOUS_ANSWER_EXPANSION
                    ? clean(question)
                    : contextual ? rewriteCodeQuestion(question, recentTurns, anchors) : clean(question);
            return new RagConversationContext(conversation.id(), rewritten, recentTurns, anchors, List.of(), contextual,
                    effectiveIntent, previousItems, List.of(), requiredChunkIds);
        }
        List<DocumentConversationAnchor> anchors = contextual ? documentAnchors(recentTurns) : List.of();
        String rewritten = effectiveIntent == ConversationIntent.PREVIOUS_ANSWER_EXPANSION
                ? clean(question)
                : contextual ? rewriteDocumentQuestion(question, recentTurns, anchors) : clean(question);
        return new RagConversationContext(conversation.id(), rewritten, recentTurns, List.of(), anchors, contextual,
                effectiveIntent, previousItems, requiredChunkIds, List.of());
    }

    public AskResponse saveDocumentTurn(
            RagConversationContext context,
            UUID parentTurnId,
            String originalQuestion,
            AskResponse response
    ) {
        if (context == null || context.conversationId() == null || response == null) {
            return response;
        }
        validateParentTurn(context.conversationId(), parentTurnId);
        RagConversationTurn turn = repository.addTurn(
                context.conversationId(),
                parentTurnId,
                clean(originalQuestion),
                context.rewrittenQuestion(),
                response.mode(),
                response.answer(),
                response.confidence(),
                objectMapper.valueToTree(response.citations()),
                objectMapper.valueToTree(response.evidence()),
                objectMapper.valueToTree(response.diagnostics()),
                metadata(context)
        );
        return response.withConversation(context.conversationId(), turn.id(), context.rewrittenQuestion());
    }

    public CodeAskResponse saveCodeTurn(
            RagConversationContext context,
            UUID parentTurnId,
            String originalQuestion,
            CodeAskResponse response
    ) {
        if (context == null || context.conversationId() == null || response == null) {
            return response;
        }
        validateParentTurn(context.conversationId(), parentTurnId);
        RagConversationTurn turn = repository.addTurn(
                context.conversationId(),
                parentTurnId,
                clean(originalQuestion),
                context.rewrittenQuestion(),
                response.mode(),
                response.answer(),
                response.confidence(),
                objectMapper.createArrayNode(),
                objectMapper.valueToTree(response.evidence()),
                objectMapper.valueToTree(response.diagnostics()),
                metadata(context)
        );
        return response.withConversation(context.conversationId(), turn.id(), context.rewrittenQuestion());
    }

    private String rewriteDocumentQuestion(
            String question,
            List<RagConversationTurnContext> recentTurns,
            List<DocumentConversationAnchor> anchors
    ) {
        String cleanQuestion = clean(question);
        if (anchors == null || anchors.isEmpty()) {
            return limitRewrite(cleanQuestion + " " + compact(recentTurns.get(0).question(), 120));
        }
        DocumentConversationAnchor anchor = anchors.get(0);
        String location = firstNonBlank(anchor.clauseNumber(), anchor.sectionTitle(), anchor.headingPath(), pageLabel(anchor.pageNumber()), anchor.title());
        String rewritten = String.join(" ",
                clean(anchor.title()),
                clean(location),
                cleanQuestion
        ).trim();
        return limitRewrite(rewritten.isBlank() ? cleanQuestion : rewritten);
    }

    private String rewriteCodeQuestion(
            String question,
            List<RagConversationTurnContext> recentTurns,
            List<CodeConversationAnchor> anchors
    ) {
        String cleanQuestion = clean(question);
        if (recentTurns == null || recentTurns.isEmpty()) {
            return cleanQuestion;
        }
        String topic = anchors == null || anchors.isEmpty()
                ? compact(recentTurns.get(0).question(), 180)
                : anchors.stream()
                .limit(4)
                .map(this::codeAnchorLabel)
                .filter(label -> !label.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse(compact(recentTurns.get(0).question(), 180));
        return limitRewrite(topic + " " + cleanQuestion);
    }

    private boolean looksContextual(String domain, String question) {
        String normalized = clean(question).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        boolean common = containsAny(normalized,
                "그 ", "그거", "그것", "이 ", "이거", "이것",
                "저 ", "저거", "해당", "위 ", "앞서", "방금",
                "이전", "같은", "계속",
                "this", "that", "above", "previous", "same", "continue");
        if (CODE.equals(domain)) {
            return common || containsAny(normalized,
                    "그 파일", "그 메서드", "그 함수",
                    "이 파일", "이 메서드", "이 함수",
                    "해당 파일", "해당 메서드", "해당 함수",
                    "same file", "same method");
        }
        return common || containsAny(normalized,
                "그 문서", "이 문서", "해당 문서",
                "그 내용", "이 내용", "해당 내용",
                "그 조항", "이 조항", "해당 조항",
                "그 페이지", "이 페이지", "해당 페이지");
    }

    private boolean looksReferenceFollowup(String question) {
        String normalized = clean(question).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "더 자세히", "좀 더 자세히", "자세히",
                "더 설명", "추가 설명", "왜 ", "어떻게",
                "more detail", "details", "elaborate", "explain more", "why", "how");
    }

    private boolean looksShortCodeFollowup(String question) {
        String normalized = clean(question).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        int terms = normalized.split("\\s+").length;
        return normalized.length() <= 80
                && terms <= 12
                && !looksStandaloneCodeTarget(question)
                && !looksBareFeatureKeyword(question)
                && looksCodeFollowupCue(normalized)
                && !containsAny(normalized, "새 대화", "다른 주제", "new topic", "unrelated");
    }

    private boolean looksCodeFollowupCue(String normalized) {
        return containsAny(normalized,
                "이건", "이것", "그건", "그것", "여기", "해당",
                "같은", "방금", "이전", "왜", "어떻게", "흐름",
                "영향", "위치", "라인", "테스트", "수정", "문제",
                "호출", "근거",
                "why", "how", "same", "this", "that", "flow", "impact", "line", "test", "call", "evidence");
    }

    private boolean looksBareFeatureKeyword(String question) {
        String normalized = clean(question).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        int terms = normalized.split("\\s+").length;
        if (terms > 3) {
            return false;
        }
        return !looksReferenceFollowup(question)
                && !looksCodeFollowupCue(normalized)
                && normalized.matches("[\\p{IsHangul}a-z0-9_\\-\\s]+");
    }

    private boolean looksStandaloneCodeTarget(String question) {
        String raw = clean(question);
        if (raw.isBlank()) {
            return false;
        }
        return raw.matches(".*[/\\\\].*")
                || raw.matches("(?i).*\\b[\\w.-]+\\.(java|js|jsx|ts|tsx|cs|py|kt|go|rb|php|cpp|c|h)\\b.*")
                || raw.matches(".*\\b[A-Z][A-Za-z0-9_]*(Controller|Service|Repository|Component|Page|View|Model|Dto|DTO)\\b.*")
                || raw.matches(".*\\b[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(.*");
    }

    private ConversationIntent classifyConversationIntent(String question, boolean hasRecentTurns) {
        if (!hasRecentTurns) {
            return ConversationIntent.NONE;
        }
        String normalized = clean(question).toLowerCase();
        if (normalized.isBlank()) {
            return ConversationIntent.NONE;
        }
        if (containsAny(normalized,
                "항목별", "각 항목", "각 근거",
                "핵심근거", "핵심 근거", "근거를",
                "위 내용", "위 답변", "방금 답변",
                "이전 답변", "확장",
                "expand", "previous answer", "by item", "by evidence", "per item")) {
            return ConversationIntent.PREVIOUS_ANSWER_EXPANSION;
        }
        return ConversationIntent.NONE;
    }

    private void validateConversationScope(RagConversationSummary conversation, UUID spaceId, String domain, UUID repositoryId) {
        if (!Objects.equals(conversation.spaceId(), spaceId)) {
            throw new IllegalArgumentException("RAG conversation workspace does not match the request.");
        }
        if (!Objects.equals(normalizeDomain(conversation.domain()), domain)) {
            throw new IllegalArgumentException("RAG conversation domain does not match the request.");
        }
        if (!Objects.equals(conversation.repositoryId(), repositoryId)) {
            throw new IllegalArgumentException("RAG conversation repository does not match the request.");
        }
    }

    private void validateParentTurn(UUID conversationId, UUID parentTurnId) {
        if (parentTurnId != null && !repository.turnBelongsToConversation(conversationId, parentTurnId)) {
            throw new IllegalArgumentException("RAG conversation parent turn does not match the conversation.");
        }
    }

    private List<PreviousAnswerItem> previousAnswerItems(RagConversationTurnContext turn) {
        if (turn == null || clean(turn.answer()).isBlank()) {
            return List.of();
        }
        Map<Integer, UUID> evidenceByCitation = evidenceByCitation(turn.evidence());
        List<PreviousAnswerItem> items = extractOutlineItems(turn.answer(), evidenceByCitation);
        if (!items.isEmpty()) {
            return items;
        }
        List<Integer> citations = citationNumbers(turn.answer());
        List<UUID> chunkIds = chunkIdsForCitations(citations, evidenceByCitation);
        if (chunkIds.isEmpty()) {
            chunkIds = evidenceByCitation.values().stream().limit(5).toList();
        }
        return List.of(new PreviousAnswerItem("Previous answer", compact(turn.answer(), 220), citations, chunkIds));
    }

    private List<PreviousAnswerItem> extractOutlineItems(String answer, Map<Integer, UUID> evidenceByCitation) {
        List<PreviousAnswerItem> items = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            Matcher matcher = OUTLINE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String text = matcher.group(1).trim();
            if (text.isBlank()) {
                continue;
            }
            List<Integer> citations = citationNumbers(text);
            List<UUID> chunkIds = chunkIdsForCitations(citations, evidenceByCitation);
            items.add(new PreviousAnswerItem(stripCitations(text), text, citations, chunkIds));
            if (items.size() >= 12) {
                break;
            }
        }
        return items;
    }

    private List<Integer> citationNumbers(String value) {
        Set<Integer> numbers = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(clean(value));
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed citation numbers.
            }
        }
        return List.copyOf(numbers);
    }

    private Map<Integer, UUID> evidenceByCitation(JsonNode evidence) {
        Map<Integer, UUID> mapping = new LinkedHashMap<>();
        if (evidence == null || !evidence.isArray()) {
            return mapping;
        }
        int index = 1;
        for (JsonNode item : evidence) {
            UUID chunkId = uuid(item.path("chunkId").asText(""));
            if (chunkId != null) {
                mapping.put(index, chunkId);
            }
            index++;
        }
        return mapping;
    }

    private List<UUID> chunkIdsForCitations(List<Integer> citations, Map<Integer, UUID> evidenceByCitation) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream()
                .map(evidenceByCitation::get)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<UUID> requiredChunkIds(List<PreviousAnswerItem> items) {
        return items.stream()
                .flatMap(item -> item.evidenceChunkIds().stream())
                .distinct()
                .limit(12)
                .toList();
    }

    private String stripCitations(String value) {
        String stripped = CITATION_PATTERN.matcher(clean(value)).replaceAll("").trim();
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 120).trim() + "...";
    }

    private List<DocumentConversationAnchor> documentAnchors(List<RagConversationTurnContext> recentTurns) {
        if (recentTurns == null || recentTurns.isEmpty()) {
            return List.of();
        }
        List<DocumentConversationAnchor> anchors = new ArrayList<>();
        for (RagConversationTurnContext turn : recentTurns) {
            JsonNode evidence = turn.evidence();
            if (evidence == null || !evidence.isArray()) {
                continue;
            }
            for (JsonNode item : evidence) {
                UUID chunkId = uuid(item.path("chunkId").asText(""));
                UUID documentId = uuid(item.path("documentId").asText(""));
                if (chunkId == null || documentId == null) {
                    continue;
                }
                JsonNode metadata = item.path("metadata");
                DocumentConversationAnchor anchor = new DocumentConversationAnchor(
                        chunkId,
                        documentId,
                        item.path("title").asText(""),
                        item.path("sourceUri").asText(""),
                        item.path("chunkIndex").asInt(0),
                        metadata.has("pageNumber") ? metadata.path("pageNumber").asInt() : null,
                        metadata.path("sectionTitle").asText(""),
                        metadata.path("headingPath").asText(""),
                        metadata.path("documentType").asText(""),
                        metadata.path("clauseNumber").asText(""),
                        metadata.path("clauseLevel").asText("")
                );
                if (anchors.stream().noneMatch(existing -> existing.chunkId().equals(anchor.chunkId()))) {
                    anchors.add(anchor);
                }
                if (anchors.size() >= 8) {
                    return anchors;
                }
            }
        }
        return anchors;
    }

    private List<CodeConversationAnchor> codeAnchors(List<RagConversationTurnContext> recentTurns) {
        if (recentTurns == null || recentTurns.isEmpty()) {
            return List.of();
        }
        List<CodeConversationAnchor> anchors = new ArrayList<>();
        for (RagConversationTurnContext turn : recentTurns) {
            JsonNode evidence = turn.evidence();
            if (evidence == null || !evidence.isArray()) {
                continue;
            }
            for (JsonNode item : evidence) {
                String filePath = item.path("filePath").asText("");
                if (filePath.isBlank()) {
                    continue;
                }
                CodeConversationAnchor anchor = new CodeConversationAnchor(
                        uuid(item.path("chunkId").asText("")),
                        filePath,
                        item.path("symbolName").asText(""),
                        item.path("className").asText(""),
                        item.path("methodName").asText(""),
                        item.path("lineStart").asInt(0),
                        item.path("lineEnd").asInt(0)
                );
                if (anchors.stream().noneMatch(existing -> sameCodeAnchor(existing, anchor))) {
                    anchors.add(anchor);
                }
                if (anchors.size() >= 8) {
                    return anchors;
                }
            }
        }
        return anchors;
    }

    private boolean sameCodeAnchor(CodeConversationAnchor left, CodeConversationAnchor right) {
        if (left.chunkId() != null && right.chunkId() != null) {
            return left.chunkId().equals(right.chunkId());
        }
        return clean(left.filePath()).equals(clean(right.filePath()))
                && clean(left.symbolName()).equals(clean(right.symbolName()))
                && clean(left.methodName()).equals(clean(right.methodName()));
    }

    private JsonNode metadata(RagConversationContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recentTurnCount", context.recentTurns() == null ? 0 : context.recentTurns().size());
        metadata.put("codeAnchorCount", context.codeAnchors() == null ? 0 : context.codeAnchors().size());
        metadata.put("documentAnchorCount", context.documentAnchors() == null ? 0 : context.documentAnchors().size());
        metadata.put("contextual", context.contextual());
        metadata.put("conversationIntent", context.conversationIntent().name());
        metadata.put("previousAnswerItemCount", context.previousAnswerItems().size());
        metadata.put("requiredDocumentChunkCount", context.requiredDocumentChunkIds().size());
        metadata.put("requiredCodeChunkCount", context.requiredCodeChunkIds().size());
        metadata.put("rewritten", context.contextual() && !clean(context.rewrittenQuestion()).isBlank());
        return objectMapper.valueToTree(metadata);
    }

    private String codeAnchorLabel(CodeConversationAnchor anchor) {
        if (anchor == null) {
            return "";
        }
        String symbol = firstNonBlank(anchor.methodName(), anchor.className(), anchor.symbolName());
        return clean(anchor.filePath()) + (clean(symbol).isBlank() ? "" : "#" + symbol);
    }

    private String pageLabel(Integer pageNumber) {
        return pageNumber == null || pageNumber <= 0 ? "" : "page " + pageNumber;
    }

    private String normalizeDomain(String domain) {
        return CODE.equalsIgnoreCase(domain) ? CODE : DOCUMENT;
    }

    private String title(String question) {
        String clean = clean(question);
        return clean.isBlank() ? "New RAG conversation" : compact(clean, 60);
    }

    private UUID uuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String limitRewrite(String value) {
        String rewritten = clean(value).replaceAll("\\s+", " ");
        return rewritten.length() <= MAX_REWRITE_CHARS ? rewritten : rewritten.substring(0, MAX_REWRITE_CHARS);
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String compact(String value, int maxChars) {
        String clean = clean(value).replaceAll("\\s+", " ");
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars).trim() + "...";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
