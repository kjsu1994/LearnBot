package com.learnbot.service.coderag.orchestration;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.service.OllamaClient;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeQuestionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns Code RAG intent routing and conversation-aware question normalization.
 *
 * <p>The class deliberately preserves the existing routing policy. Moving the
 * policy behind this boundary lets retrieval and answer orchestration consume a
 * stable decision without accumulating more question-specific branches.</p>
 */
public final class CodeQuestionRouter {
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final RagPipelineService pipelineService;
    private final boolean commitRoutingAvailable;

    public CodeQuestionRouter(
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService,
            boolean commitRoutingAvailable
    ) {
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.pipelineService = pipelineService;
        this.commitRoutingAvailable = commitRoutingAvailable;
    }

    public RagPipelineService.CodeRagRouteDecision initialRoute(
            String originalQuestion,
            String requestedMode,
            RagConversationContext conversationContext,
            boolean combinedPlanning
    ) {
        if (combinedPlanning) {
            return RagPipelineService.CodeRagRouteDecision.fallback("routing delegated to repository planner");
        }
        boolean releasedPrimarySlot = ollamaClient.hasPrimaryRequestInFlight();
        if (releasedPrimarySlot) {
            ollamaClient.finishPrimaryRequest();
        }
        try {
            return pipelineService.routeCodeRagIntent(
                    originalQuestion,
                    requestedMode,
                    conversationContext,
                    commitRoutingAvailable
            );
        } finally {
            if (releasedPrimarySlot) {
                ollamaClient.beginPrimaryRequest();
            }
        }
    }

    public String routedCommitQuestion(
            String originalQuestion,
            RagPipelineService.CodeRagRouteDecision routeDecision
    ) {
        String commitRef = safe(routeDecision == null ? null : routeDecision.commitRef());
        return commitRef.isBlank() ? safe(originalQuestion) : safe(originalQuestion) + "\nCommit reference: " + commitRef;
    }

    public String routedQuestion(String fallback, RagPipelineService.CodeRagRouteDecision routeDecision) {
        if (routeDecision == null) {
            return safe(fallback);
        }
        String query = routeDecision.queries().stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        if (!query.isBlank()) {
            return safe(fallback) + "\n\nRetrieval hints from route decision:\n" + query;
        }
        String symbol = safe(routeDecision.targetSymbol());
        String file = safe(routeDecision.targetFile());
        String combined = (file + " " + symbol).trim();
        return combined.isBlank() ? safe(fallback) : safe(fallback) + "\n\nRetrieval hints from route decision:\n" + combined;
    }

    public String routedMode(String requestedMode, RagPipelineService.CodeRagRouteDecision routeDecision) {
        if (routeDecision == null) {
            return requestedMode;
        }
        String mode = safe(routeDecision.mode());
        if (!mode.isBlank() && !"auto".equalsIgnoreCase(mode)) {
            return mode;
        }
        return switch (routeDecision.route()) {
            case CODE_OVERVIEW_FLOW -> "flow";
            case LOCATE_SYMBOL -> "locate";
            case EXPLAIN_METHOD -> "method";
            case IMPACT_ANALYSIS -> "impact";
            case EXPAND_PREVIOUS_ANSWER, ANSWER_FROM_PRIOR -> safe(requestedMode).isBlank() ? "overview" : requestedMode;
            default -> requestedMode;
        };
    }

    public CodeAskResponse withRouteDiagnostics(
            CodeAskResponse response,
            RagPipelineService.CodeRagRouteDecision routeDecision,
            boolean commitFallbackUsed
    ) {
        return new CodeAskResponse(
                response.mode(),
                response.answer(),
                response.evidence(),
                response.confidence(),
                routeDiagnostics(response.diagnostics(), routeDecision, commitFallbackUsed),
                response.conversationId(),
                response.turnId(),
                response.rewrittenQuestion()
        );
    }

    public List<String> routeDiagnostics(
            List<String> diagnostics,
            RagPipelineService.CodeRagRouteDecision routeDecision,
            boolean commitFallbackUsed
    ) {
        List<String> notes = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        if (routeDecision == null) {
            return notes;
        }
        notes.add("Agentic RAG route: route=" + routeDecision.route()
                + ", confidence=" + routeDecision.confidence()
                + ", mode=" + safe(routeDecision.mode())
                + ", queries=" + routeDecision.queries().size()
                + ", attempted=" + routeDecision.attempted()
                + ", fallback=" + routeDecision.fallback()
                + ", commitFallback=" + commitFallbackUsed
                + ", reason=" + safe(routeDecision.reason()) + ".");
        return notes;
    }

    public String effectiveQuestion(String originalQuestion, RagConversationContext conversationContext) {
        if (conversationContext == null || !conversationContext.contextual()) {
            return safe(originalQuestion);
        }
        if (conversationContext.previousAnswerExpansion()) {
            return safe(originalQuestion);
        }
        String rewritten = safe(conversationContext.rewrittenQuestion());
        return rewritten.isBlank() ? safe(originalQuestion) : rewritten;
    }

    public String questionPrompt(
            String originalQuestion,
            String effectiveQuestion,
            RagConversationContext conversationContext
    ) {
        if (conversationContext != null && conversationContext.previousAnswerExpansion()) {
            return "Original user question:\n" + originalQuestion
                    + "\n\nThis is a request to expand the previous answer. Keep the previous answer outline and expand each item using only the current source-code context.";
        }
        if (conversationContext == null || !conversationContext.contextual()
                || safe(effectiveQuestion).equals(safe(originalQuestion))) {
            return "Question:\n" + originalQuestion;
        }
        return "Original user question:\n" + originalQuestion
                + "\n\nConversation-aware search question:\n" + effectiveQuestion
                + "\n\nAnswer the original user question. Use the conversation-aware question only to resolve references.";
    }

    public int safeLimit(CodeQuestionMode questionMode, Integer limit) {
        int defaultLimit = questionMode == CodeQuestionMode.OVERVIEW
                ? Math.max(properties.getCode().getTopK(), 14)
                : properties.getCode().getTopK();
        return limit == null ? defaultLimit : Math.max(1, Math.min(limit, 24));
    }

    public CodeQuestionMode classify(
            String question,
            String mode,
            RagConversationContext conversationContext
    ) {
        boolean autoMode = mode == null || mode.isBlank() || "auto".equalsIgnoreCase(mode.trim());
        CodeQuestionMode requested = CodeQuestionMode.from(mode);
        if (!autoMode) {
            return requested;
        }
        if (!previousAnswerExpansion(conversationContext)
                && conversationContext != null && conversationContext.contextual()) {
            CodeQuestionMode previousMode = previousTurnMode(conversationContext);
            if (previousMode != null) {
                return previousMode;
            }
        }
        return CodeQuestionMode.OVERVIEW;
    }

    private CodeQuestionMode previousTurnMode(RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.recentTurns() == null) {
            return null;
        }
        return conversationContext.recentTurns().stream()
                .map(RagConversationTurnContext::mode)
                .filter(mode -> mode != null && !mode.isBlank())
                .map(String::trim)
                .flatMap(mode -> java.util.Arrays.stream(CodeQuestionMode.values())
                        .filter(candidate -> candidate.value().equalsIgnoreCase(mode))
                        .findFirst()
                        .stream())
                .filter(this::canInheritAutoMode)
                .findFirst()
                .orElse(null);
    }

    private boolean canInheritAutoMode(CodeQuestionMode mode) {
        return mode == CodeQuestionMode.LOCATE
                || mode == CodeQuestionMode.EXPLAIN_METHOD
                || mode == CodeQuestionMode.UI_EVENT
                || mode == CodeQuestionMode.REASONING;
    }

    private boolean previousAnswerExpansion(RagConversationContext conversationContext) {
        return conversationContext != null && conversationContext.previousAnswerExpansion();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
