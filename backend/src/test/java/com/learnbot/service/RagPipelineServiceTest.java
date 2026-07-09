package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagPipelineServiceTest {
    @Test
    void queryRewriteFallsBackToDeterministicQueriesWhenModelReturnsInvalidJson() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());

        when(ollamaClient.chat(anyString(), anyString())).thenReturn("not json");

        RagPipelineService.QueryPlan plan = service.buildQueryPlan(
                "What changed recently?",
                RagPipelineService.Domain.CODE,
                List.of("latest commit changes")
        );

        assertThat(plan.rewriteUsed()).isFalse();
        assertThat(plan.rewriteFailed()).isTrue();
        assertThat(plan.queries()).contains("What changed recently?", "latest commit changes");
    }

    @Test
    void codeRouteUsesStructuredFormatAndRetriesTruncatedJson() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(
                new OllamaClient.ChatResult("{\"route\":\"CODE_SEARCH\"", "length", true, 20, 12, "http://ollama", "test", "auxiliary", false),
                new OllamaClient.ChatResult("""
                        {"route":"CODE_SEARCH","mode":"flow","confidence":0.8,"queries":["call flow"],"commitRef":"","targetFile":"","targetSymbol":"","reason":"ok"}
                        """, "stop", true, 20, 40, "http://ollama", "test", "auxiliary", false)
        );

        RagPipelineService.CodeRagRouteDecision decision = service.routeCodeRagIntent("call flow", "auto", null, false);

        assertThat(decision.route()).isEqualTo(RagPipelineService.CodeRagRoute.CODE_SEARCH);
        assertThat(decision.mode()).isEqualTo("flow");
        assertThat(decision.queries()).containsExactly("call flow");

        ArgumentCaptor<Object> formatCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ollamaClient, times(2)).chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                formatCaptor.capture()
        );
        assertThat(formatCaptor.getAllValues()).allSatisfy(format -> {
            assertThat(format).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) format).get("type")).isEqualTo("object");
        });
    }

    @Test
    void codeEvidenceAdjudicationAddsLlmEvidenceClassificationMetadata() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setCodeEvidenceAdjudicationEnabled(true);
        RagPipelineService service = new RagPipelineService(ollamaClient, properties);
        CodeSearchResult candidate = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/repository/CodeRepository.java",
                "method",
                "replaceGraph",
                "CodeRepository",
                "replaceGraph",
                "com.learnbot.repository",
                null,
                null,
                1,
                998,
                1065,
                "INSERT INTO code_graph_nodes ... INSERT INTO code_graph_edges ...",
                0.7,
                Map.of()
        );

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"selected":[{"index":1,"score":0.96,"evidenceKind":"direct_code","implementationPhase":"GRAPH_STORAGE","responsibility":"graph_persistence","coverageGroup":"graph_persistence","mustUse":true,"supportedClaims":["persists graph nodes and edges"],"notSupportedClaims":["performs graph traversal"],"rankReason":"direct storage SQL","reason":"storage SQL"}],"reason":"ok"}
                """, "stop", true, 100, 80, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceAdjudication adjudication = service.adjudicateCodeEvidence(
                "How are graph nodes and edges stored?",
                "overview",
                List.of(candidate),
                4
        );

        assertThat(adjudication.used()).isTrue();
        assertThat(adjudication.results().get(0).metadata())
                .containsEntry("llmEvidenceKind", "direct_code")
                .containsEntry("llmImplementationPhase", "GRAPH_STORAGE")
                .containsEntry("llmEvidenceResponsibility", "graph_persistence")
                .containsEntry("llmEvidenceCoverageGroup", "graph_persistence")
                .containsEntry("llmEvidenceSlateRank", 1)
                .containsEntry("llmEvidenceSlateMustUse", true)
                .containsEntry("llmEvidenceClassificationSource", "llm_adjudication");
    }

    @Test
    void codeEvidenceAdjudicationCanSelectBeyondLegacyTopTenCandidates() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        List<CodeSearchResult> candidates = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            candidates.add(new CodeSearchResult(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "LearnBot",
                    "backend/src/main/java/com/learnbot/service/Candidate" + index + ".java",
                    "method",
                    "candidate" + index,
                    "Candidate" + index,
                    "candidate" + index,
                    "com.learnbot.service",
                    null,
                    null,
                    index,
                    10,
                    24,
                    "candidate " + index + " content",
                    1.0 - (index * 0.01),
                    Map.of()
            ));
        }

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"selected":[{"index":12,"score":0.97,"evidenceKind":"direct_code","implementationPhase":"ANSWER_GENERATION","responsibility":"answer_context","coverageGroup":"response_intake","mustUse":true,"supportedClaims":["handles completion response"],"notSupportedClaims":["claims queued work"],"rankReason":"direct completion handler","reason":"best completion evidence"}],"reason":"selected completion evidence"}
                """, "stop", true, 400, 120, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceAdjudication adjudication = service.adjudicateCodeEvidence(
                "How does a worker response get completed and stored?",
                "flow",
                candidates,
                4
        );

        assertThat(adjudication.used()).isTrue();
        assertThat(adjudication.results().get(0).filePath())
                .isEqualTo("backend/src/main/java/com/learnbot/service/Candidate12.java");
        assertThat(adjudication.results().get(0).metadata())
                .containsEntry("llmEvidenceCoverageGroup", "response_intake")
                .containsEntry("llmEvidenceSlateRank", 1);
    }

    @Test
    void codeEvidenceFollowUpParsesRequiredEvidenceGroups() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        CodeSearchResult candidate = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/CodeSearchService.java",
                "method",
                "expandGraph",
                "CodeSearchService",
                "expandGraph",
                "com.learnbot.service",
                null,
                null,
                1,
                184,
                216,
                "private List<CodeSearchResult> expandGraph(...) { ... }",
                0.72,
                Map.of()
        );

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["graph schema","graph persistence"],"followUpQueries":["graph storage nodes edges"],"queryAreas":["persistence"],"requiredEvidenceGroups":["graph_schema","graph_persistence","queue_claim","response_intake","persistence_update","async_transport","unknown","graph_schema"],"reason":"need storage proof"}
                """, "stop", true, 120, 90, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "How are graph nodes and edges stored?",
                "overview",
                List.of(candidate),
                4
        );

        assertThat(plan.enough()).isFalse();
        assertThat(plan.requiredEvidenceGroups()).containsExactly(
                "graph_schema",
                "graph_persistence",
                "queue_claim",
                "response_intake",
                "persistence_update",
                "async_transport"
        );
        assertThat(plan.followUpQueries()).containsExactly("graph storage nodes edges");
    }

    @Test
    void codeEvidenceSearchPlanParsesChecklistItems() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RuntimeTuningService runtimeTuningService = mock(RuntimeTuningService.class);
        when(runtimeTuningService.codeEvidenceDecisionModel()).thenReturn(1);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties(), runtimeTuningService);

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.PRIMARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"usable":true,"confidence":0.86,"queries":["/api/code/ask CodeController ask CodeRagService"],"checklist":[{"claimId":"request-entrypoint","evidenceGroup":"request_intake","goal":"find endpoint handling /api/code/ask","queries":["CodeController ask /api/code/ask"]},{"claimId":"graph-expansion","evidenceGroup":"graph_traversal","goal":"find graph expansion implementation","queries":["CodeSearchService expandGraph graphRelatedChunks"]}],"reason":"phase-specific plan"}
                """, "stop", true, 200, 160, "http://ollama", "test", "primary", false));

        RagPipelineService.CodeEvidenceSearchPlan plan = service.planCodeEvidenceSearch(
                "Explain /api/code/ask from controller to graph expansion and answer generation",
                "flow",
                "__learnbot__/project-context.md",
                4
        );

        assertThat(plan.usable()).isTrue();
        assertThat(plan.checklist()).hasSize(2);
        assertThat(plan.checklist().get(0).claimId()).isEqualTo("request-entrypoint");
        assertThat(plan.checklist().get(1).evidenceGroup()).isEqualTo("graph_traversal");
        assertThat(plan.checklist().get(1).queries()).containsExactly("CodeSearchService expandGraph graphRelatedChunks");
    }

    @Test
    void codeEvidenceFollowUpPromptCarriesChecklistForward() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        RagPipelineService service = new RagPipelineService(ollamaClient, new LearnBotProperties());
        CodeSearchResult candidate = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/CodeRagService.java",
                "method",
                "askPrioritized",
                "CodeRagService",
                "askPrioritized",
                "com.learnbot.service",
                null,
                null,
                1,
                170,
                220,
                "private CodeAskResponse askPrioritized(...) { ... }",
                0.72,
                Map.of()
        );
        List<RagPipelineService.CodeEvidenceChecklistItem> checklist = List.of(
                new RagPipelineService.CodeEvidenceChecklistItem(
                        "graph-expansion",
                        "graph_traversal",
                        "find concrete graph expansion implementation",
                        List.of("CodeSearchService expandGraph graphRelatedChunks")
                )
        );

        when(ollamaClient.chatResult(
                anyString(),
                anyString(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        )).thenReturn(new OllamaClient.ChatResult("""
                {"enough":false,"missingAreas":["graph expansion"],"followUpQueries":["CodeSearchService expandGraph"],"queryAreas":["graph expansion"],"requiredEvidenceGroups":["graph_traversal"],"reason":"need concrete traversal evidence"}
                """, "stop", true, 120, 90, "http://ollama", "test", "auxiliary", false));

        RagPipelineService.CodeEvidenceFollowUpPlan plan = service.planCodeEvidenceFollowUp(
                "Explain /api/code/ask graph expansion",
                "flow",
                List.of(candidate),
                2,
                checklist
        );

        assertThat(plan.checklist()).containsExactlyElementsOf(checklist);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(
                anyString(),
                promptCaptor.capture(),
                eq(OllamaClient.ChatRole.AUXILIARY),
                anyInt(),
                any(Duration.class),
                any()
        );
        assertThat(promptCaptor.getValue())
                .contains("Required evidence checklist")
                .contains("graph-expansion")
                .contains("find concrete graph expansion implementation");
    }

    @Test
    void codeEvidenceCanBeSufficientWhenStructuredEvidenceIsStrongEvenIfTermsDiffer() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());
        CodeSearchResult result = new CodeSearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LearnBot",
                "backend/src/main/java/com/learnbot/service/LoginService.java",
                "method",
                "login",
                "LoginService",
                "login",
                "com.learnbot.service",
                null,
                null,
                1,
                10,
                32,
                "public LoginResponse login(...) { authenticate(); issueToken(); }",
                0.82,
                Map.of("language", "java")
        );

        RagPipelineService.EvidenceAssessment assessment = service.assessCode("sign-in flow", List.of(result), 2, 1);

        assertThat(assessment.sufficient()).isTrue();
    }

    @Test
    void answerSelfCheckRejectsCitationOutsideEvidenceRange() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());

        RagPipelineService.AnswerAssessment assessment = service.assessAnswer("Answer based on evidence [2].", 1, true);

        assertThat(assessment.acceptable()).isFalse();
        assertThat(assessment.reason()).isEqualTo("citation out of range");
    }

    @Test
    void answerSelfCheckRejectsLengthStoppedGeneration() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());

        RagPipelineService.AnswerAssessment assessment = service.assessAnswer(
                "근거에 따르면 관리자 권한 관리가 추가되었습니다 [1].",
                1,
                true,
                "length"
        );

        assertThat(assessment.acceptable()).isFalse();
        assertThat(assessment.reason()).isEqualTo("model stopped before finishing");
    }

    @Test
    void answerSelfCheckRejectsIncompleteFinalSentence() {
        RagPipelineService service = new RagPipelineService(mock(OllamaClient.class), new LearnBotProperties());

        RagPipelineService.AnswerAssessment assessment = service.assessAnswer(
                "근거에 따르면 설정 클래스에 Pipeline이라는 정",
                1,
                false
        );

        assertThat(assessment.acceptable()).isFalse();
        assertThat(assessment.reason()).isEqualTo("answer appears incomplete");
    }
}
