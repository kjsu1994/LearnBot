package com.learnbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeConversationAnchor;
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
    private static final int REWRITE_TURN_LIMIT = 3;
    private static final int MAX_REWRITE_CHARS = 1800;

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
            return new RagConversationContext(null, clean(question), List.of(), List.of(), false);
        }
        String normalizedDomain = normalizeDomain(domain);
        RagConversationSummary conversation = conversationId == null
                ? repository.create(user.id(), spaceId, normalizedDomain, repositoryId, title(question))
                : repository.findSummary(user.id(), conversationId)
                .orElseThrow(() -> new IllegalArgumentException("RAG conversation was not found."));
        List<RagConversationTurnContext> recentTurns = repository.recentTurnContexts(conversation.id(), RECENT_TURN_LIMIT);
        boolean contextual = looksContextual(question) && recentTurns != null && !recentTurns.isEmpty();
        if (CODE.equals(normalizedDomain)) {
            List<CodeConversationAnchor> anchors = contextual ? codeAnchors(recentTurns) : List.of();
            String rewritten = contextual ? rewriteCodeQuestion(question, recentTurns, anchors) : clean(question);
            return new RagConversationContext(conversation.id(), rewritten, recentTurns, anchors, contextual);
        }
        return new RagConversationContext(conversation.id(), rewriteDocumentQuestion(domain, question, recentTurns), recentTurns, List.of(), contextual);
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

    private String rewriteDocumentQuestion(String domain, String question, List<RagConversationTurnContext> recentTurns) {
        String cleanQuestion = clean(question);
        if (recentTurns == null || recentTurns.isEmpty() || !looksContextual(cleanQuestion)) {
            return cleanQuestion;
        }
        StringBuilder builder = new StringBuilder(cleanQuestion);
        builder.append("\n\nPrevious conversation context for ").append(normalizeDomain(domain)).append(" RAG:\n");
        for (int i = 0; i < Math.min(recentTurns.size(), REWRITE_TURN_LIMIT); i++) {
            RagConversationTurnContext turn = recentTurns.get(i);
            builder.append("- Q: ").append(compact(turn.question(), 180)).append("\n");
            builder.append("  A: ").append(compact(turn.answer(), 260)).append("\n");
            String evidence = evidenceSummary(turn.evidence(), normalizeDomain(domain));
            if (!evidence.isBlank()) {
                builder.append("  Evidence: ").append(evidence).append("\n");
            }
        }
        return limitRewrite(builder.toString());
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
        StringBuilder builder = new StringBuilder();
        builder.append("사용자 질문: ").append(cleanQuestion).append('\n');
        builder.append("이전 대화 주제: ");
        if (anchors == null || anchors.isEmpty()) {
            builder.append(compact(recentTurns.get(0).question(), 180));
        } else {
            builder.append(anchors.stream()
                    .limit(6)
                    .map(this::anchorLabel)
                    .filter(label -> !label.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(compact(recentTurns.get(0).question(), 180)));
        }
        builder.append('\n');
        builder.append("독립 질문: ");
        builder.append(cleanQuestion);
        if (anchors != null && !anchors.isEmpty()) {
            builder.append(" (관련 코드: ");
            builder.append(anchors.stream()
                    .limit(4)
                    .map(this::anchorLabel)
                    .filter(label -> !label.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(""));
            builder.append(')');
        }
        return limitRewrite(builder.toString());
    }

    private boolean looksContextual(String question) {
        String normalized = clean(question).toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "이거", "그거", "저거", "위", "앞", "방금", "이전", "아까", "그 파일", "그 메서드", "그 함수",
                "이 파일", "이 메서드", "이 함수", "이어", "계속", "흐름", "호출", "영향", "근거", "출처",
                "this", "that", "above", "previous", "continue", "same file", "same method");
    }

    private String evidenceSummary(JsonNode evidence, String domain) {
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (JsonNode item : evidence) {
            if (count >= 4) {
                break;
            }
            if (CODE.equals(domain)) {
                appendPart(builder, item.path("filePath").asText(""));
                appendPart(builder, item.path("symbolName").asText(""));
            } else {
                appendPart(builder, item.path("title").asText(""));
                appendPart(builder, item.path("chunkId").asText(""));
            }
            count++;
        }
        return compact(builder.toString(), 520);
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
                if (anchors.stream().noneMatch(existing -> sameAnchor(existing, anchor))) {
                    anchors.add(anchor);
                }
                if (anchors.size() >= 8) {
                    return anchors;
                }
            }
        }
        return anchors;
    }

    private boolean sameAnchor(CodeConversationAnchor left, CodeConversationAnchor right) {
        if (left.chunkId() != null && right.chunkId() != null) {
            return left.chunkId().equals(right.chunkId());
        }
        return clean(left.filePath()).equals(clean(right.filePath()))
                && clean(left.symbolName()).equals(clean(right.symbolName()))
                && clean(left.methodName()).equals(clean(right.methodName()));
    }

    private String anchorLabel(CodeConversationAnchor anchor) {
        if (anchor == null) {
            return "";
        }
        String symbol = !clean(anchor.methodName()).isBlank()
                ? anchor.methodName()
                : !clean(anchor.className()).isBlank()
                ? anchor.className()
                : anchor.symbolName();
        return clean(anchor.filePath()) + (clean(symbol).isBlank() ? "" : "#" + symbol);
    }

    private UUID uuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void appendPart(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(value.trim());
    }

    private JsonNode metadata(RagConversationContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recentTurnCount", context.recentTurns() == null ? 0 : context.recentTurns().size());
        metadata.put("codeAnchorCount", context.codeAnchors() == null ? 0 : context.codeAnchors().size());
        metadata.put("contextual", context.contextual());
        metadata.put("rewritten", context.contextual() && !clean(context.rewrittenQuestion()).isBlank());
        return objectMapper.valueToTree(metadata);
    }

    private String normalizeDomain(String domain) {
        if (CODE.equalsIgnoreCase(domain)) {
            return CODE;
        }
        return DOCUMENT;
    }

    private String title(String question) {
        String clean = clean(question);
        if (clean.isBlank()) {
            return "새 RAG 대화";
        }
        return compact(clean, 60);
    }

    private String limitRewrite(String value) {
        String rewritten = clean(value);
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
