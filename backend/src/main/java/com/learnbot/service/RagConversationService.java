package com.learnbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeConversationAnchor;
import com.learnbot.dto.DocumentConversationAnchor;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationDetail;
import com.learnbot.dto.RagConversationSummary;
import com.learnbot.dto.RagConversationTurn;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.repository.RagConversationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RagConversationService {
    public static final String DOCUMENT = "DOCUMENT";
    public static final String CODE = "CODE";

    private static final int RECENT_TURN_LIMIT = 5;
    private static final int MAX_REWRITE_CHARS = 520;

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
        List<RagConversationTurnContext> recentTurns = repository.recentTurnContexts(conversation.id(), RECENT_TURN_LIMIT);
        boolean contextual = looksContextual(normalizedDomain, question) && recentTurns != null && !recentTurns.isEmpty();
        if (CODE.equals(normalizedDomain)) {
            List<CodeConversationAnchor> anchors = contextual ? codeAnchors(recentTurns) : List.of();
            String rewritten = contextual ? rewriteCodeQuestion(question, recentTurns, anchors) : clean(question);
            return new RagConversationContext(conversation.id(), rewritten, recentTurns, anchors, List.of(), contextual);
        }
        List<DocumentConversationAnchor> anchors = contextual ? documentAnchors(recentTurns) : List.of();
        String rewritten = contextual ? rewriteDocumentQuestion(question, recentTurns, anchors) : clean(question);
        return new RagConversationContext(conversation.id(), rewritten, recentTurns, List.of(), anchors, contextual);
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
        String location = firstNonBlank(anchor.sectionTitle(), anchor.headingPath(), pageLabel(anchor.pageNumber()), anchor.title());
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
                "이거", "그거", "저거", "위", "앞", "방금", "이전", "아까",
                "이어", "계속", "근거", "출처", "예외", "조건",
                "this", "that", "above", "previous", "continue");
        if (CODE.equals(domain)) {
            return common || containsAny(normalized,
                    "그 파일", "그 메서드", "그 함수", "이 파일", "이 메서드", "이 함수", "흐름", "호출", "영향",
                    "same file", "same method");
        }
        return common || containsAny(normalized,
                "이 문서", "그 문서", "위 내용", "앞 내용", "그 조항", "그 표", "그 페이지",
                "해당 문서", "해당 조항", "페이지", "표", "조항", "요약");
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
                        metadata.path("documentType").asText("")
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
        return pageNumber == null || pageNumber <= 0 ? "" : pageNumber + "페이지";
    }

    private String normalizeDomain(String domain) {
        return CODE.equalsIgnoreCase(domain) ? CODE : DOCUMENT;
    }

    private String title(String question) {
        String clean = clean(question);
        return clean.isBlank() ? "새 RAG 대화" : compact(clean, 60);
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
