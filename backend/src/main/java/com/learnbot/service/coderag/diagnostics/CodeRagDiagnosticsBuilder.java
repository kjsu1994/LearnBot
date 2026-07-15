package com.learnbot.service.coderag.diagnostics;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeSourceClassifier;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.evidence.CodeEvidenceRanker;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the ordered, externally visible diagnostics attached to a Code RAG answer.
 *
 * <p>The records in this class are an adapter boundary for request-scoped state currently owned
 * by the orchestrator. They intentionally contain values rather than orchestration services so
 * diagnostics remain deterministic and independently testable.</p>
 */
@Component
public final class CodeRagDiagnosticsBuilder {
    private static final int EFFECTIVE_QUESTION_PREVIEW_CHARS = 180;

    private final CodeEvidenceRanker evidenceRanker;

    public CodeRagDiagnosticsBuilder(CodeEvidenceRanker evidenceRanker) {
        this.evidenceRanker = evidenceRanker;
    }

    public List<String> build(Request request) {
        Request safeRequest = request == null ? Request.empty() : request;
        boolean returnedFallback = safeRequest.llmUnavailable()
                || (safeRequest.answerRewritten() && !safeRequest.answerKeptAfterStreamValidation());
        List<String> notes = new ArrayList<>(baseDiagnostics(safeRequest, returnedFallback));

        CitationTrace citation = safeRequest.citation();
        notes.add("RAG quality trace: answerChars=" + safeRequest.answer().length()
                + ", citedReferences=" + citation.referencedCount()
                + ", invalidCitationRefs=" + citation.invalidCount()
                + ", citationCoverage=" + citation.coveragePercent() + "%"
                + ", fallback=" + returnedFallback
                + ", retry=" + safeRequest.answerRetried()
                + ", continuation=" + safeRequest.answerContinued()
                + ", doneReason=" + safe(safeRequest.doneReason(), "none") + ".");
        if (safeRequest.quality().observed()) {
            notes.add(safeRequest.quality().summary());
        }
        if (!citation.summary().isBlank()) {
            notes.add("Citation support: " + citation.summary());
        }
        notes.add(codeEvidenceSelectionSummary(
                safeRequest.selectedEvidence(), safeRequest.contextBudgetDropped()));

        RetrievalTrace retrieval = safeRequest.retrieval();
        if (retrieval != null && retrieval.deterministicPlan() != null) {
            DeterministicPlanTrace plan = retrieval.deterministicPlan();
            notes.add("Code query planner: intent=" + plan.intent()
                    + ", queryCount=" + plan.queries().size()
                    + ", auxiliaryQueries=" + Math.max(0, plan.queries().size() - 1)
                    + ", originalOnlyFallback=" + plan.originalOnlyFallback() + ".");
        }
        if (isBroadMode(safeRequest.questionMode())) {
            long projectContext = safeRequest.selectedEvidence().stream()
                    .filter(result -> isProjectContext(result.chunkType()))
                    .count();
            long distinctFiles = safeRequest.selectedEvidence().stream()
                    .map(CodeSearchResult::filePath)
                    .distinct()
                    .count();
            notes.add("Code question mode was classified as " + safeRequest.questionMode().name()
                    + "; answer context used " + projectContext + " project context chunks and "
                    + distinctFiles + " distinct files.");
        }
        if (retrieval != null && retrieval.iteration() > 1) {
            notes.add("RAG pipeline ran " + (retrieval.iteration() - 1)
                    + " LLM-planned Retrieval Iteration(s) and merged them with the initial evidence.");
        }
        if (retrieval != null && retrieval.followUpPlan() != null) {
            RagPipelineService.CodeEvidenceFollowUpPlan plan = retrieval.followUpPlan();
            notes.add("Code evidence Retrieval Iteration planner: attempted=" + plan.attempted()
                    + ", enough=" + plan.enough()
                    + ", followUpQueriesUsed=" + retrieval.followUpQueriesUsed()
                    + ", followUpCandidatesAdded=" + retrieval.followUpCandidateCount()
                    + ", followUpSelected=" + safeRequest.selectedEvidence().stream()
                    .filter(CodeRagDiagnosticsBuilder::isLlmFollowUpEvidence)
                    .count()
                    + ", missingAreas=" + plan.missingAreas()
                    + ", queryAreas=" + plan.queryAreas()
                    + ", reason=" + safe(plan.reason(), "") + ".");
            notes.add("Code RAG trace: traceId=" + retrieval.traceId()
                    + ", indexVersion=" + safe(retrieval.indexVersion(), "unknown")
                    + ", mapRevision=" + retrieval.mapRevision()
                    + ", hypothesisVersion=" + plan.hypothesisVersion()
                    + ", premiseDisposition=" + plan.premiseDisposition()
                    + ", claimResults=" + plan.claimResults().stream()
                    .map(result -> result.claimId() + ":" + result.status())
                    .toList()
                    + ", terminalStatus=" + retrieval.terminalStatus() + ".");
        }
        if (retrieval != null && retrieval.queryPlan() != null) {
            RagPipelineService.QueryPlan plan = retrieval.queryPlan();
            notes.add("Code query rewrite status: attempted=" + plan.rewriteAttempted()
                    + ", used=" + plan.rewriteUsed()
                    + ", failed=" + plan.rewriteFailed()
                    + ", reason=" + plan.reason()
                    + ", queryCount=" + plan.queries().size() + ".");
            if (plan.rewriteUsed()) {
                notes.add("RAG pipeline used LLM-planned query expansion as an auxiliary code retrieval signal.");
            }
            if (plan.rewriteFailed()) {
                notes.add("RAG query rewrite failed, so deterministic hybrid code search was used.");
            }
        }
        if (retrieval != null && retrieval.assessment() != null && !retrieval.assessment().sufficient()) {
            notes.add("Code evidence sufficiency check remained weak: "
                    + String.join(", ", retrieval.assessment().reasons()));
        }
        appendGraphDiagnostics(notes, safeRequest.selectedEvidence());
        appendRankingDiagnostics(notes, safeRequest.selectedEvidence());
        if (safeRequest.answerRetried()) {
            notes.add("Answer self-check retried generation once before returning the final answer.");
        }
        if (safeRequest.answerContinued()) {
            notes.add("Answer generation reached the model output limit and was automatically continued before returning.");
        }
        if (safeRequest.answerKeptAfterStreamValidation()) {
            notes.add("Streaming answer was kept after self-check flagged the final text; review citations and confidence before relying on it.");
        }
        appendRouteDiagnostics(notes, safeRequest.route());
        appendConversationDiagnostics(notes, safeRequest.conversation());
        return List.copyOf(notes);
    }

    private List<String> baseDiagnostics(Request request, boolean returnedFallback) {
        long distinctFiles = request.retrievedEvidence().stream()
                .map(CodeSearchResult::filePath)
                .distinct()
                .count();
        List<String> notes = new ArrayList<>();
        notes.add("검색된 코드 근거 " + request.retrievedEvidence().size() + "개, 파일 " + distinctFiles + "개 중 "
                + request.selectedEvidence().size() + "개를 답변 컨텍스트로 사용했습니다.");
        if (request.llmUnavailable()) {
            notes.add("LLM 호출이 실패해 검색 근거 기반 fallback 답변을 반환했습니다.");
        }
        if (request.answerRewritten() && !request.answerKeptAfterStreamValidation()) {
            notes.add("LLM 응답이 너무 짧거나 인용이 부족해, 검색 근거 기반 답변으로 대체했습니다.");
        }
        if (request.lowConfidence()) {
            notes.add("직접적인 정의/호출 근거가 약하므로 후보 파일로 검토해야 합니다.");
        }
        return notes;
    }

    private String codeEvidenceSelectionSummary(
            List<CodeSearchResult> answerResults,
            int contextBudgetDropped
    ) {
        Map<String, Long> typeCounts = answerResults.stream()
                .map(result -> safe(result.chunkType(), "unknown"))
                .collect(Collectors.groupingBy(
                        type -> type.isBlank() ? "unknown" : type,
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> sourceRoles = answerResults.stream()
                .map(CodeSourceClassifier::sourceRole)
                .collect(Collectors.groupingBy(
                        role -> role == null || role.isBlank() ? "unknown" : role,
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> parsers = answerResults.stream()
                .map(CodeRagDiagnosticsBuilder::parserName)
                .collect(Collectors.groupingBy(
                        parser -> parser.isBlank() ? "unknown" : parser,
                        LinkedHashMap::new,
                        Collectors.counting()));
        long structured = answerResults.stream()
                .filter(result -> isStructured(result.chunkType()))
                .count();
        long fallbackLineWindows = answerResults.stream()
                .filter(result -> "line_window".equals(parserName(result)))
                .count();
        int structuredPercent = answerResults.isEmpty()
                ? 0
                : (int) Math.round((structured * 100.0) / answerResults.size());
        long graphExpanded = answerResults.stream().filter(CodeRagDiagnosticsBuilder::isGraphExpanded).count();
        long required = answerResults.stream().filter(CodeRagDiagnosticsBuilder::isRequiredConversationPinned).count();
        long llmAdjudicated = answerResults.stream()
                .filter(result -> metadataFlag(result, "llmEvidenceAdjudicationSelected"))
                .count();
        long llmFollowUp = answerResults.stream().filter(CodeRagDiagnosticsBuilder::isLlmFollowUpEvidence).count();
        return "Evidence selection: selected=" + answerResults.size()
                + ", budgetDropped=" + Math.max(0, contextBudgetDropped)
                + ", chunkTypes=" + typeCounts
                + ", sourceRoles=" + sourceRoles
                + ", parsers=" + parsers
                + ", structured=" + structured + "/" + answerResults.size() + " (" + structuredPercent + "%)"
                + ", lineWindowFallback=" + fallbackLineWindows
                + ", graphExpanded=" + graphExpanded
                + ", requiredPinned=" + required
                + ", llmAdjudicated=" + llmAdjudicated
                + ", llmFollowUp=" + llmFollowUp + ".";
    }

    private void appendGraphDiagnostics(List<String> notes, List<CodeSearchResult> evidence) {
        if (evidence.stream().noneMatch(CodeRagDiagnosticsBuilder::isGraphExpanded)) {
            return;
        }
        notes.add("Code GraphRAG expanded related evidence through indexed code relationships.");
        CodeEvidenceRanker.GraphReliabilitySummary graph = evidenceRanker.summarizeGraph(evidence);
        notes.add("Graph evidence: " + graph.expanded() + " expanded chunks, "
                + graph.strong() + " strong, "
                + graph.medium() + " medium, "
                + graph.partial() + " partial.");
        if (!graph.edgeSummary().isBlank()) {
            notes.add("Top graph edges: " + graph.edgeSummary() + ".");
        }
    }

    private void appendRankingDiagnostics(List<String> notes, List<CodeSearchResult> evidence) {
        if (evidence.stream().noneMatch(result -> result.metadata() != null
                && result.metadata().containsKey("evidenceScore"))) {
            return;
        }
        notes.add("Code evidence was ranked with deterministic evidence scoring before answer context selection.");
        if (!evidenceRanker.debug()) {
            return;
        }
        String rankingDetails = evidence.stream()
                .limit(5)
                .map(result -> {
                    Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
                    return result.filePath() + " score=" + evidenceRanker.score(result)
                            + " reliability=" + String.valueOf(metadata.getOrDefault("graphReliability", "none"))
                            + " reason=" + String.valueOf(metadata.getOrDefault("evidenceRankReason", ""));
                })
                .collect(Collectors.joining("; "));
        notes.add("Evidence ranking debug: " + rankingDetails);
    }

    private void appendRouteDiagnostics(List<String> notes, RouteTrace routeTrace) {
        if (routeTrace == null || routeTrace.decision() == null) {
            return;
        }
        RagPipelineService.CodeRagRouteDecision decision = routeTrace.decision();
        notes.add("Agentic RAG route: route=" + decision.route()
                + ", confidence=" + decision.confidence()
                + ", mode=" + safe(decision.mode(), "")
                + ", queries=" + decision.queries().size()
                + ", attempted=" + decision.attempted()
                + ", fallback=" + decision.fallback()
                + ", commitFallback=" + routeTrace.commitFallbackUsed()
                + ", reason=" + safe(decision.reason(), "") + ".");
    }

    private void appendConversationDiagnostics(List<String> notes, ConversationTrace conversation) {
        if (conversation == null || !conversation.contextual()) {
            return;
        }
        notes.add("대화 컨텍스트를 사용했습니다. 이전 코드 근거 "
                + conversation.codeAnchorCount()
                + "개 중 pinned 후보 " + conversation.pinnedCandidateCount()
                + "개, 최종 답변 근거 " + conversation.pinnedUsedCount() + "개를 반영했습니다.");
        if (!conversation.originalQuestion().equals(conversation.effectiveQuestion())) {
            notes.add("후속 질문 검색용 독립 질문을 생성했습니다: "
                    + trimInline(conversation.effectiveQuestion()));
        }
        if (conversation.pinnedCandidateCount() == 0 && conversation.codeAnchorCount() > 0) {
            notes.add("이전 코드 근거를 직접 조회하지 못해 일반 코드 검색으로 폴백했습니다.");
        }
    }

    private static boolean isBroadMode(CodeQuestionMode mode) {
        return mode == CodeQuestionMode.OVERVIEW
                || mode == CodeQuestionMode.CALL_FLOW
                || mode == CodeQuestionMode.IMPACT
                || mode == CodeQuestionMode.REASONING;
    }

    private static boolean isStructured(String chunkType) {
        return "class".equals(chunkType)
                || "method".equals(chunkType)
                || "function".equals(chunkType)
                || "constructor".equals(chunkType)
                || "record".equals(chunkType)
                || "enum".equals(chunkType)
                || "component".equals(chunkType)
                || "event_handler".equals(chunkType)
                || "xaml_event".equals(chunkType)
                || "xaml_view".equals(chunkType)
                || "xaml_binding".equals(chunkType)
                || "xaml_control".equals(chunkType)
                || "winforms_control".equals(chunkType)
                || "view_model".equals(chunkType)
                || "command".equals(chunkType)
                || isProjectContext(chunkType);
    }

    private static boolean isProjectContext(String chunkType) {
        return "project_structure".equals(chunkType)
                || "repository_summary".equals(chunkType)
                || "directory_summary".equals(chunkType)
                || "file_summary".equals(chunkType);
    }

    private static boolean isGraphExpanded(CodeSearchResult result) {
        return metadataFlag(result, "graphExpanded");
    }

    private static boolean isRequiredConversationPinned(CodeSearchResult result) {
        return metadataFlag(result, "conversationRequired");
    }

    private static boolean isLlmFollowUpEvidence(CodeSearchResult result) {
        return metadataFlag(result, "llmFollowUpEvidence");
    }

    private static boolean metadataFlag(CodeSearchResult result, String key) {
        return result != null
                && result.metadata() != null
                && Boolean.TRUE.equals(result.metadata().get(key));
    }

    private static String parserName(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        Object parser = result.metadata().getOrDefault("parser", result.metadata().get("strategy"));
        return parser == null ? "" : String.valueOf(parser);
    }

    private static String trimInline(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= EFFECTIVE_QUESTION_PREVIEW_CHARS) {
            return compact;
        }
        return compact.substring(0, EFFECTIVE_QUESTION_PREVIEW_CHARS).trim() + "...";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Request(
            CodeQuestionMode questionMode,
            List<CodeSearchResult> retrievedEvidence,
            List<CodeSearchResult> selectedEvidence,
            String answer,
            String doneReason,
            boolean lowConfidence,
            boolean llmUnavailable,
            boolean answerRewritten,
            boolean answerRetried,
            boolean answerContinued,
            boolean answerKeptAfterStreamValidation,
            CitationTrace citation,
            QualityTrace quality,
            RetrievalTrace retrieval,
            int contextBudgetDropped,
            RouteTrace route,
            ConversationTrace conversation
    ) {
        public Request {
            questionMode = questionMode == null ? CodeQuestionMode.LOCATE : questionMode;
            retrievedEvidence = retrievedEvidence == null ? List.of() : List.copyOf(retrievedEvidence);
            selectedEvidence = selectedEvidence == null ? List.of() : List.copyOf(selectedEvidence);
            answer = answer == null ? "" : answer;
            doneReason = doneReason == null ? "" : doneReason;
            citation = citation == null ? CitationTrace.empty() : citation;
            quality = quality == null ? QualityTrace.empty() : quality;
            contextBudgetDropped = Math.max(0, contextBudgetDropped);
        }

        public static Request empty() {
            return new Request(
                    CodeQuestionMode.LOCATE, List.of(), List.of(), "", "",
                    false, false, false, false, false, false,
                    CitationTrace.empty(), QualityTrace.empty(), null, 0, null, null);
        }
    }

    public record CitationTrace(
            int referencedCount,
            int invalidCount,
            int coveragePercent,
            String summary
    ) {
        public CitationTrace {
            referencedCount = Math.max(0, referencedCount);
            invalidCount = Math.max(0, invalidCount);
            coveragePercent = Math.max(0, Math.min(100, coveragePercent));
            summary = summary == null ? "" : summary.trim();
        }

        public static CitationTrace empty() {
            return new CitationTrace(0, 0, 0, "");
        }
    }

    public record QualityTrace(boolean observed, String summary) {
        public QualityTrace {
            summary = summary == null ? "" : summary.trim();
        }

        public static QualityTrace empty() {
            return new QualityTrace(false, "");
        }
    }

    public record DeterministicPlanTrace(
            String intent,
            List<String> queries,
            boolean originalOnlyFallback
    ) {
        public DeterministicPlanTrace {
            intent = intent == null ? "" : intent;
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }

    public record RetrievalTrace(
            RagPipelineService.EvidenceAssessment assessment,
            RagPipelineService.QueryPlan queryPlan,
            DeterministicPlanTrace deterministicPlan,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            int followUpQueriesUsed,
            int followUpCandidateCount,
            int iteration,
            String traceId,
            String indexVersion,
            long mapRevision,
            String terminalStatus
    ) {
        public RetrievalTrace {
            followUpQueriesUsed = Math.max(0, followUpQueriesUsed);
            followUpCandidateCount = Math.max(0, followUpCandidateCount);
            iteration = Math.max(0, iteration);
            traceId = traceId == null ? "" : traceId;
            indexVersion = indexVersion == null ? "" : indexVersion;
            terminalStatus = terminalStatus == null ? "" : terminalStatus;
        }
    }

    public record RouteTrace(
            RagPipelineService.CodeRagRouteDecision decision,
            boolean commitFallbackUsed
    ) {
    }

    public record ConversationTrace(
            boolean contextual,
            int codeAnchorCount,
            int pinnedCandidateCount,
            int pinnedUsedCount,
            String originalQuestion,
            String effectiveQuestion
    ) {
        public ConversationTrace {
            codeAnchorCount = Math.max(0, codeAnchorCount);
            pinnedCandidateCount = Math.max(0, pinnedCandidateCount);
            pinnedUsedCount = Math.max(0, pinnedUsedCount);
            originalQuestion = originalQuestion == null ? "" : originalQuestion;
            effectiveQuestion = effectiveQuestion == null ? "" : effectiveQuestion;
        }
    }
}
