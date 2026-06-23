package com.learnbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AnswerEvidence;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.CodeConversationAnchor;
import com.learnbot.dto.ConversationIntent;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationSummary;
import com.learnbot.dto.RagConversationTurn;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.repository.RagConversationRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagConversationServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagConversationRepository repository = mock(RagConversationRepository.class);
    private final RagConversationService service = new RagConversationService(repository, objectMapper);
    private final AppUser user = new AppUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE");

    @Test
    void standaloneDocumentTermsDoNotPullPreviousConversationContext() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(conversationId, spaceId, RagConversationService.DOCUMENT, null)));
        when(repository.recentTurnContexts(conversationId, 5)).thenReturn(List.of(turnWithDocumentEvidence()));

        RagConversationContext context = service.prepare(
                user,
                spaceId,
                RagConversationService.DOCUMENT,
                null,
                conversationId,
                "summary table clause page",
                true
        );

        assertThat(context.contextual()).isFalse();
        assertThat(context.documentAnchors()).isEmpty();
        assertThat(context.rewrittenQuestion()).isEqualTo("summary table clause page");
    }

    @Test
    void explicitDocumentReferenceKeepsConversationContextAndAnchors() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(conversationId, spaceId, RagConversationService.DOCUMENT, null)));
        when(repository.recentTurnContexts(conversationId, 5)).thenReturn(List.of(turnWithDocumentEvidence()));

        RagConversationContext context = service.prepare(
                user,
                spaceId,
                RagConversationService.DOCUMENT,
                null,
                conversationId,
                "that document summary",
                true
        );

        assertThat(context.contextual()).isTrue();
        assertThat(context.documentAnchors()).hasSize(1);
        assertThat(context.rewrittenQuestion()).contains("policy.pdf").contains("that document summary");
    }

    @Test
    void previousAnswerExpansionExtractsOutlineAndRequiredEvidence() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID firstChunkId = UUID.randomUUID();
        UUID secondChunkId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(conversationId, spaceId, RagConversationService.DOCUMENT, null)));
        when(repository.recentTurnContexts(conversationId, 5)).thenReturn(List.of(turnWithDocumentEvidence(
                """
                        ## Access control [1]
                        - Audit schedule [2]
                        """,
                firstChunkId,
                secondChunkId
        )));

        RagConversationContext context = service.prepare(
                user,
                spaceId,
                RagConversationService.DOCUMENT,
                null,
                conversationId,
                "more detail by item",
                true
        );

        assertThat(context.conversationIntent()).isEqualTo(ConversationIntent.PREVIOUS_ANSWER_EXPANSION);
        assertThat(context.contextual()).isTrue();
        assertThat(context.rewrittenQuestion()).isEqualTo("more detail by item");
        assertThat(context.previousAnswerItems()).hasSize(2);
        assertThat(context.requiredDocumentChunkIds()).containsExactly(firstChunkId, secondChunkId);
    }

    @Test
    void shortCodeFollowupUsesPreviousCodeAnchors() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(conversationId, spaceId, RagConversationService.CODE, null)));
        when(repository.recentTurnContexts(conversationId, 5)).thenReturn(List.of(turnWithCodeEvidence(chunkId)));

        RagConversationContext context = service.prepare(
                user,
                spaceId,
                RagConversationService.CODE,
                null,
                conversationId,
                "more detail",
                true
        );

        assertThat(context.conversationIntent()).isEqualTo(ConversationIntent.REFERENCE_FOLLOWUP);
        assertThat(context.contextual()).isTrue();
        assertThat(context.codeAnchors()).extracting(CodeConversationAnchor::chunkId).containsExactly(chunkId);
        assertThat(context.rewrittenQuestion()).contains("CodeRagService.java").contains("askConversational").contains("more detail");
        assertThat(context.previousAnswerExpansion()).isFalse();
    }

    @Test
    void previousAnswerExpansionRequiresExplicitPreviousAnswerExpansionSignal() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(conversationId, spaceId, RagConversationService.CODE, null)));
        when(repository.recentTurnContexts(conversationId, 5)).thenReturn(List.of(turnWithCodeEvidence(chunkId)));

        RagConversationContext detailContext = service.prepare(
                user,
                spaceId,
                RagConversationService.CODE,
                null,
                conversationId,
                "details",
                true
        );
        RagConversationContext expansionContext = service.prepare(
                user,
                spaceId,
                RagConversationService.CODE,
                null,
                conversationId,
                "expand previous answer by item",
                true
        );

        assertThat(detailContext.conversationIntent()).isEqualTo(ConversationIntent.REFERENCE_FOLLOWUP);
        assertThat(detailContext.requiredCodeChunkIds()).isEmpty();
        assertThat(expansionContext.conversationIntent()).isEqualTo(ConversationIntent.PREVIOUS_ANSWER_EXPANSION);
        assertThat(expansionContext.requiredCodeChunkIds()).contains(chunkId);
    }

    @Test
    void shortCodeQuestionWithoutPreviousAnchorsDoesNotBecomeContextualOnlyByConversationId() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(conversationId, spaceId, RagConversationService.CODE, null)));
        when(repository.recentTurnContexts(conversationId, 5)).thenReturn(List.of(new RagConversationTurnContext(
                "What does this repository do?",
                "It explains the service.",
                objectMapper.createArrayNode()
        )));

        RagConversationContext context = service.prepare(
                user,
                spaceId,
                RagConversationService.CODE,
                null,
                conversationId,
                "login",
                true
        );

        assertThat(context.contextual()).isFalse();
        assertThat(context.codeAnchors()).isEmpty();
        assertThat(context.conversationIntent()).isEqualTo(ConversationIntent.NONE);
    }

    @Test
    void shortCodeQuestionWithExplicitClassTargetDoesNotBecomeContextualFollowup() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(conversationId, spaceId, RagConversationService.CODE, null)));
        when(repository.recentTurnContexts(conversationId, 5)).thenReturn(List.of(turnWithCodeEvidence(chunkId)));

        RagConversationContext context = service.prepare(
                user,
                spaceId,
                RagConversationService.CODE,
                null,
                conversationId,
                "AuthController",
                true
        );

        assertThat(context.contextual()).isFalse();
        assertThat(context.codeAnchors()).isEmpty();
        assertThat(context.conversationIntent()).isEqualTo(ConversationIntent.NONE);
    }

    @Test
    void conversationWorkspaceScopeMustMatchRequest() {
        UUID conversationId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(
                conversationId,
                UUID.randomUUID(),
                RagConversationService.DOCUMENT,
                null
        )));

        assertThatThrownBy(() -> service.prepare(
                user,
                UUID.randomUUID(),
                RagConversationService.DOCUMENT,
                null,
                conversationId,
                "same method",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
    }

    @Test
    void conversationDomainScopeMustMatchRequest() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(
                conversationId,
                spaceId,
                RagConversationService.DOCUMENT,
                null
        )));

        assertThatThrownBy(() -> service.prepare(
                user,
                spaceId,
                RagConversationService.CODE,
                null,
                conversationId,
                "same method",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain");
    }

    @Test
    void conversationRepositoryScopeMustMatchRequest() {
        UUID spaceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID requestedRepositoryId = UUID.randomUUID();
        UUID existingRepositoryId = UUID.randomUUID();
        when(repository.findSummary(user.id(), conversationId)).thenReturn(Optional.of(summary(
                conversationId,
                spaceId,
                RagConversationService.CODE,
                existingRepositoryId
        )));

        assertThatThrownBy(() -> service.prepare(
                user,
                spaceId,
                RagConversationService.CODE,
                requestedRepositoryId,
                conversationId,
                "same method",
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repository");
    }

    @Test
    void parentTurnMustBelongToSameConversationBeforeSaving() {
        UUID conversationId = UUID.randomUUID();
        UUID parentTurnId = UUID.randomUUID();
        RagConversationContext context = new RagConversationContext(conversationId, "question", List.of());
        AskResponse response = new AskResponse("qa", "answer [1]", List.of(), List.of());
        when(repository.turnBelongsToConversation(conversationId, parentTurnId)).thenReturn(false);

        assertThatThrownBy(() -> service.saveDocumentTurn(context, parentTurnId, "question", response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent turn");
        verify(repository, never()).addTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void validParentTurnStillSavesWithConversationMetadata() {
        UUID conversationId = UUID.randomUUID();
        UUID parentTurnId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        RagConversationContext context = new RagConversationContext(conversationId, "rewritten question", List.of());
        AskResponse response = new AskResponse("qa", "answer [1]", List.of(), List.of(
                new AnswerEvidence(1, UUID.randomUUID(), UUID.randomUUID(), "policy.pdf", "file://policy.pdf", "FILE", 0, "preview", 0.9, Map.of())
        ));
        when(repository.turnBelongsToConversation(conversationId, parentTurnId)).thenReturn(true);
        when(repository.addTurn(eq(conversationId), eq(parentTurnId), eq("original question"), eq("rewritten question"),
                eq("qa"), eq("answer [1]"), any(), any(), any(), any(), any()))
                .thenReturn(new RagConversationTurn(
                        turnId,
                        conversationId,
                        parentTurnId,
                        "original question",
                        "rewritten question",
                        "qa",
                        "answer [1]",
                        "normal",
                        objectMapper.createArrayNode(),
                        objectMapper.createArrayNode(),
                        objectMapper.createArrayNode(),
                        objectMapper.createObjectNode(),
                        OffsetDateTime.now()
                ));

        AskResponse saved = service.saveDocumentTurn(context, parentTurnId, "original question", response);

        assertThat(saved.conversationId()).isEqualTo(conversationId);
        assertThat(saved.turnId()).isEqualTo(turnId);
        assertThat(saved.rewrittenQuestion()).isEqualTo("rewritten question");
    }

    private RagConversationSummary summary(UUID conversationId, UUID spaceId, String domain, UUID repositoryId) {
        return new RagConversationSummary(conversationId, spaceId, domain, repositoryId, "Conversation", OffsetDateTime.now(), OffsetDateTime.now());
    }

    private RagConversationTurnContext turnWithDocumentEvidence() {
        return turnWithDocumentEvidence("It requires MFA [1].", UUID.randomUUID());
    }

    private RagConversationTurnContext turnWithDocumentEvidence(String answer, UUID... chunkIds) {
        List<Map<String, Object>> evidence = java.util.Arrays.stream(chunkIds)
                .map(chunkId -> Map.<String, Object>of(
                "chunkId", chunkId.toString(),
                "documentId", UUID.randomUUID().toString(),
                "title", "policy.pdf",
                "sourceUri", "file://policy.pdf",
                "chunkIndex", 2,
                "metadata", Map.of(
                        "sectionTitle", "Access",
                        "headingPath", "Security > Access",
                        "documentType", "policy"
                )
        ))
                .toList();
        return new RagConversationTurnContext("What is the policy?", answer, objectMapper.valueToTree(evidence));
    }

    private RagConversationTurnContext turnWithCodeEvidence(UUID chunkId) {
        List<Map<String, Object>> evidence = List.of(Map.of(
                "chunkId", chunkId.toString(),
                "filePath", "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "symbolName", "askConversational",
                "className", "CodeRagService",
                "methodName", "askConversational",
                "lineStart", 120,
                "lineEnd", 140
        ));
        return new RagConversationTurnContext("How does askConversational work?", "## Ask flow [1]\n- Uses prioritized RAG [1].", objectMapper.valueToTree(evidence));
    }
}
