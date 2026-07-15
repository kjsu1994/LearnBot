package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.repository.CodeRepository;
import com.learnbot.service.coderag.orchestration.CodeRagOrchestrator;
import com.learnbot.service.coderag.evidence.CodeEvidenceCoverageGate;
import com.learnbot.service.coderag.evidence.CodeEvidenceRanker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backward-compatible public facade for Code RAG.
 *
 * <p>Request orchestration lives under {@code com.learnbot.service.coderag}; this type intentionally
 * keeps the existing controller and test construction contracts stable.</p>
 */
@Service
public class CodeRagService {
    private final CodeRagOrchestrator orchestrator;

    @Autowired
    public CodeRagService(CodeRagOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public CodeRagService(
            CodeSearchService searchService,
            CodeRepository codeRepository,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService,
            CodeEvidenceRanker evidenceRanker,
            RagMetricsService ragMetricsService
    ) {
        this(new CodeRagOrchestrator(
                searchService, codeRepository, referenceService, commitInsightService, ollamaClient,
                properties, pipelineService, evidenceRanker, ragMetricsService));
    }

    public CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService,
            CodeEvidenceRanker evidenceRanker
    ) {
        this(new CodeRagOrchestrator(
                searchService, referenceService, commitInsightService, ollamaClient,
                properties, pipelineService, evidenceRanker));
    }

    public CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService
    ) {
        this(new CodeRagOrchestrator(
                searchService, referenceService, commitInsightService, ollamaClient, properties, pipelineService));
    }

    CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this(new CodeRagOrchestrator(
                searchService, referenceService, commitInsightService, ollamaClient, properties));
    }

    CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this(new CodeRagOrchestrator(searchService, referenceService, ollamaClient, properties));
    }

    public CodeAskResponse ask(UUID repositoryId, String question, String mode, Integer limit) {
        return orchestrator.ask(repositoryId, question, mode, limit);
    }

    public CodeAskResponse ask(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            String mode,
            Integer limit
    ) {
        return orchestrator.ask(repositoryId, selectedSpaceId, spaceIds, question, mode, limit);
    }

    public CodeAskResponse askConversational(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            String mode,
            Integer limit,
            RagConversationContext conversationContext
    ) {
        return orchestrator.askConversational(
                repositoryId, selectedSpaceId, spaceIds, question, mode, limit, conversationContext);
    }

    public CodeAskResponse askStreaming(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            String mode,
            Integer limit,
            CodeAnswerStreamSink streamSink
    ) {
        return orchestrator.askStreaming(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, streamSink);
    }

    public CodeAskResponse askConversationalStreaming(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            String mode,
            Integer limit,
            RagConversationContext conversationContext,
            CodeAnswerStreamSink streamSink
    ) {
        return orchestrator.askConversationalStreaming(
                repositoryId, selectedSpaceId, spaceIds, question, mode, limit, conversationContext, streamSink);
    }

    public static String retrievalOperationKey(RagPipelineService.CodeSearchOperation operation) {
        return CodeRagOrchestrator.retrievalOperationKey(operation);
    }

    static String operationResultHandles(
            RagPipelineService.CodeSearchOperation operation,
            List<CodeSearchResult> results
    ) {
        return CodeRagOrchestrator.operationResultHandles(operation, results);
    }

    static boolean operationProducesFocusedEvidence(
            RagPipelineService.CodeSearchOperation operation,
            CodeSearchResult result
    ) {
        return CodeRagOrchestrator.operationProducesFocusedEvidence(operation, result);
    }

    static Map<String, Object> mergeEvidenceMetadata(
            CodeSearchResult preferred,
            CodeSearchResult current,
            CodeSearchResult incoming
    ) {
        return CodeRagOrchestrator.mergeEvidenceMetadata(preferred, current, incoming);
    }

    static boolean shouldBlockAnswerGeneration(
            CodeEvidenceCoverageGate.Outcome outcome,
            String terminalStatus,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan
    ) {
        return CodeRagOrchestrator.shouldBlockAnswerGeneration(outcome, terminalStatus, followUpPlan);
    }

    public interface CodeAnswerStreamSink extends com.learnbot.service.coderag.answer.CodeAnswerStreamSink {
    }
}
