package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
}
