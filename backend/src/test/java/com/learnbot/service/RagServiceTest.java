package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.ConversationIntent;
import com.learnbot.dto.DocumentConversationAnchor;
import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.dto.PreviousAnswerItem;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class RagServiceTest {
    @Test
    void spreadsheetCountUsesDeterministicCountAndLlmNarration() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        UUID documentId = UUID.randomUUID();
        String question = "총 몇명이야?";

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(searchResult(documentId, UUID.randomUUID(), 0, "육아기단축근로 대상자.xlsx")));
        when(documentRepository.listDocumentChunks(documentId)).thenReturn(List.of(
                chunk(0, """
                        Sheet Sheet1 Row 1: C2=연번 | C3=직종 | C4=이름 | C5=본부
                        Sheet Sheet1 Row 2: C2=ROW()-1 | C3=일반직 | C4=김현일 | C5=경영지원본부
                        Sheet Sheet1 Row 3: C2=ROW()-1 | C3=일반직 | C4=안소민 | C5=경영지원본부
                        """),
                chunk(1, "Sheet Sheet1 Row 4: C2=ROW()-1 | C3=상담직 | C4=오용숙 | C5=경영지원본부")
        ));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("총 3명입니다. 1행을 헤더로 보고 2~4행의 이름 값이 있는 행을 계산했습니다 [1]."));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).contains("3명");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.evidence()).hasSize(2);
        assertThat(response.confidence()).isEqualTo("높음");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("서버가 문서 전체 청크를 기준으로 계산"));
    }

    @Test
    void spreadsheetCountFallsBackWhenLlmAnswerIsBroken() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        UUID documentId = UUID.randomUUID();
        String question = "총 몇명이야?";

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(searchResult(documentId, UUID.randomUUID(), 0, "육아기단축근로 대상자.xlsx")));
        when(documentRepository.listDocumentChunks(documentId)).thenReturn(List.of(
                chunk(0, """
                        Sheet Sheet1 Row 1: C2=연번 | C3=직종 | C4=이름 | C5=본부
                        Sheet Sheet1 Row 2: C2=ROW()-1 | C3=일반직 | C4=김현일 | C5=경영지원본부
                        Sheet Sheet1 Row 3: C2=ROW()-1 | C3=일반직 | C4=안소민 | C5=경영지원본부
                        """)
        ));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("제"));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).contains("총 2명");
        assertThat(response.answer()).contains("계산 기준");
        assertThat(response.confidence()).isEqualTo("높음");
    }

    @Test
    void spreadsheetCountDoesNotDropFirstRowWhenHeaderIsMissing() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        UUID documentId = UUID.randomUUID();
        String question = "총 몇명이야?";

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(searchResult(documentId, UUID.randomUUID(), 0, "육아기단축근로 대상자.xlsx")));
        when(documentRepository.listDocumentChunks(documentId)).thenReturn(List.of(
                chunk(0, """
                        Sheet Sheet1 Row 8: C2=ROW()-1 | C3=VLOOKUP(D8,Sheet!A:N,6,FALSE) | C4=김환진 | C5=관세행정운영본부
                        Sheet Sheet1 Row 9: C2=ROW()-1 | C3=VLOOKUP(D9,Sheet!A:N,6,FALSE) | C4=서세덕 | C5=관세행정운영본부
                        """)
        ));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("제"));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).contains("총 2명");
        assertThat(response.answer()).contains("별도 헤더 행을 확정하지 않고");
    }

    @Test
    void generalDocumentQuestionCompressesLongContextBeforeCallingLlm() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "기간제 단시간 파견 근로자 차별예방을 위해 뭐가 개선되는거야?";
        String longContent = "반복 배경 설명 ".repeat(250)
                + "차별 예방을 위해 임금, 상여금, 복리후생, 교육훈련, 고충처리 절차의 차별적 처우를 점검하고 개선해야 합니다. "
                + "반복 부록 설명 ".repeat(250);

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                        "기간제·단시간·파견 근로자 차별 예방 가이드라인.pdf",
                        "application/pdf",
                        longContent)));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("임금, 복리후생, 교육훈련, 고충처리 절차의 차별적 처우를 점검하고 개선합니다 [1]."));

        AskResponse response = service.ask(question, null, "qa");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("차별 예방");
        assertThat(promptCaptor.getValue().length()).isLessThan(1000);
        assertThat(response.answer()).contains("임금");
    }

    @Test
    void generalDocumentQuestionFallsBackToExtractedEvidenceWhenLlmAnswerIsBroken() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "기간제 단시간 파견 근로자 차별예방을 위해 뭐가 개선되는거야?";
        String content = "차별 예방을 위해 임금, 정기상여금, 복리후생, 교육훈련, 휴가, 고충처리 등에서 불합리한 차이를 점검하고 개선해야 합니다.";

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                        "기간제·단시간·파견 근로자 차별 예방 가이드라인.pdf",
                        "application/pdf",
                        content)));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("제"));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).contains("## 결론");
        assertThat(response.answer()).contains("임금");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.confidence()).isIn("높음", "보통");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("대체"));
    }

    @Test
    void genericDocumentFallbackUsesStructuredSections() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "What is the benefits policy?";
        String content = "The benefits policy includes health checks, education support, and vacation support.";

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                        "benefits-policy.pdf",
                        "application/pdf",
                        content)));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("x"));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).contains("## ");
        assertThat(response.answer()).contains("health checks");
        assertThat(response.answer()).contains("[1]");
    }

    @Test
    void clauseExplanationQuestionUsesStructuredDocumentPromptInsteadOfLocationPrompt() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "이 규정의 적용대상과 조건, 예외를 설명해줘";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "복무규정.pdf",
                "application/pdf",
                "제10조 적용대상은 임직원이며, 조건은 사전 승인입니다. 예외는 긴급 상황입니다.");

        when(searchService.searchDetailed(anyString(), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("결론: 적용대상은 임직원입니다. 조건과 예외는 근거에 따릅니다 [1]."));

        AskResponse response = service.ask(question, null, "qa");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(systemCaptor.capture(), anyString(), anyInt());
        assertThat(systemCaptor.getValue()).contains("규정/조항 답변 추가 규칙", "적용 대상", "조건", "예외·제한");
        assertThat(systemCaptor.getValue()).doesNotContain("위치/찾기 답변 추가 규칙");
        assertThat(response.mode()).isEqualTo("qa");
        assertThat(response.answer()).contains("[1]");
    }

    @Test
    void locationQuestionUsesLocationPrompt() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "이 조항 위치와 페이지 알려줘";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 7,
                "복무규정.pdf",
                "application/pdf",
                "제10조 적용대상은 임직원입니다.");

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("복무규정.pdf의 chunk 7에서 확인됩니다 [1]."));

        AskResponse response = service.ask(question, null, "qa");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(systemCaptor.capture(), anyString(), anyInt());
        assertThat(systemCaptor.getValue()).contains("위치/찾기 답변 추가 규칙");
        assertThat(systemCaptor.getValue()).doesNotContain("규정/조항형 답변 추가 규칙");
        assertThat(response.answer()).contains("[1]");
    }

    @Test
    void processFlowQuestionUsesOverviewPromptNotProcedurePrompt() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "전체 프로세스 흐름을 설명해줘";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 2,
                "업무가이드.pdf",
                "application/pdf",
                "접수 이후 검토, 승인, 통보 순서로 업무가 흐릅니다.");

        when(searchService.searchDetailed(anyString(), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("전체 흐름은 접수, 검토, 승인, 통보입니다 [1]."));

        AskResponse response = service.ask(question, null, "qa");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(systemCaptor.capture(), anyString(), anyInt());
        assertThat(systemCaptor.getValue()).contains("요약/개요 질문");
        assertThat(systemCaptor.getValue()).doesNotContain("규정/조항 답변 추가 규칙");
        assertThat(response.answer()).contains("[1]");
    }

    @Test
    void procedureQuestionUsesStructuredProcedurePrompt() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "승인 절차와 예외 조건을 알려줘";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 3,
                "업무규정.pdf",
                "application/pdf",
                "승인 절차는 신청, 검토, 승인 순서이며 예외 조건은 긴급 처리입니다.");

        when(searchService.searchDetailed(anyString(), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("승인 절차는 신청, 검토, 승인 순서이며 예외 조건은 긴급 처리입니다 [1]."));

        AskResponse response = service.ask(question, null, "qa");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).chatResult(systemCaptor.capture(), anyString(), anyInt());
        assertThat(systemCaptor.getValue()).contains("규정/조항 답변 추가 규칙", "절차·판단 기준");
        assertThat(systemCaptor.getValue()).doesNotContain("위치/찾기 답변 추가 규칙");
        assertThat(response.answer()).contains("[1]");
    }

    @Test
    void deterministicQueryPlannerAddsMultiAspectDocumentQueries() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        String question = "Explain the approval procedure and exception conditions";
        SearchResult procedure = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "approval-policy.pdf",
                "application/pdf",
                "The approval procedure starts with manager review and then compliance approval.");
        SearchResult exception = searchResult(UUID.randomUUID(), UUID.randomUUID(), 1,
                "approval-policy.pdf",
                "application/pdf",
                "Exception conditions apply during emergency operations and must be recorded.");

        when(searchService.searchDetailed(anyString(), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(0, String.class);
                    List<SearchResult> results;
                    if (query.contains("procedure steps approval process")) {
                        results = List.of(procedure);
                    } else if (query.contains("exceptions conditions limits")) {
                        results = List.of(exception);
                    } else {
                        results = List.of();
                    }
                    return new SearchService.SearchResponse(
                            results,
                            new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                    );
                });
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The approval procedure requires manager and compliance review [1]. Exception conditions apply during emergencies [2]."));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.evidence()).hasSize(2);
        assertThat(response.answer()).contains("[1]", "[2]");
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("Document query planner").contains("intent=PROCEDURE").contains("auxiliaryQueries=2"));
        verify(searchService).searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED"));
        verify(searchService).searchDetailed(argThat(query -> query.contains("procedure steps approval process")), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED"));
        verify(searchService).searchDetailed(argThat(query -> query.contains("exceptions conditions limits")), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED"));
    }

    @Test
    void documentEvidenceRankingAddsMetadataAndPromotesDirectMatch() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "Tell me the security policy";
        SearchResult weak = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "general-guide.pdf",
                "application/pdf",
                "This is a general guide.");
        SearchResult direct = searchResult(UUID.randomUUID(), UUID.randomUUID(), 1,
                "security-policy.pdf",
                "application/pdf",
                "The security policy requires MFA and access reviews.");

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(weak, direct));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("The security policy requires MFA and access reviews [1]."));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.evidence().get(0).title()).isEqualTo("security-policy.pdf");
        assertThat(response.evidence().get(0).metadata()).containsKeys("evidenceScore", "evidenceRole", "evidenceRankReason");
    }

    @Test
    void documentEvidenceSelectionPenalizesLowQualityWebEvidence() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "Tell me the security policy";
        SearchResult lowQualityWeb = searchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                "security-web.html",
                "https://example.com/security",
                "text/html",
                "The security policy requires MFA and access reviews.",
                Map.of("extractionQualityGrade", "LOW", "extractionQualityScore", 20)
        );
        SearchResult direct = searchResult(UUID.randomUUID(), UUID.randomUUID(), 1,
                "security-policy.pdf",
                "application/pdf",
                "The security policy requires MFA and access reviews.");

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(lowQualityWeb, direct));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("The security policy requires MFA and access reviews [1]."));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.evidence().get(0).title()).isEqualTo("security-policy.pdf");
        assertThat(response.evidence()).anySatisfy(evidence ->
                assertThat(evidence.metadata()).containsEntry("lowQualityWebEvidence", true));
        assertThat(response.diagnostics()).anySatisfy(note ->
                assertThat(note).contains("Evidence selection").contains("lowQualityWebSelected=1"));
    }

    @Test
    void adjacentDocumentChunksCanBeAddedAsSupportingEvidence() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "Tell me the security policy";
        UUID documentId = UUID.randomUUID();
        SearchResult seed = searchResult(documentId, UUID.randomUUID(), 3,
                "security-policy.pdf",
                "application/pdf",
                "The security policy requires access reviews.");
        SearchResult adjacent = searchResult(documentId, UUID.randomUUID(), 4,
                "security-policy.pdf",
                "application/pdf",
                "MFA applies to every administrator account.");

        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), any(), isNull()))
                .thenReturn(List.of(seed));
        when(documentRepository.adjacentChunksBatch(any(), eq(1), isNull(SearchFilter.class), any(), isNull()))
                .thenReturn(List.of(new DocumentRepository.AdjacentChunkCandidate(adjacent, seed.chunkId(), 1, seed.score())));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("The security policy requires access reviews and MFA [1][2]."));

        AskResponse response = service.ask(question, null, "qa", List.of(UUID.randomUUID()), null);

        assertThat(response.evidence()).hasSize(2);
        assertThat(response.evidence().get(1).metadata()).containsEntry("adjacentExpanded", true);
        assertThat(response.evidence().get(1).metadata()).containsEntry("evidenceRole", "adjacent");
    }

    @Test
    void overviewQuestionExpandsContextChunkToOriginalSectionEvidence() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "Give me an overview of this document";
        UUID documentId = UUID.randomUUID();
        UUID contextChunkId = UUID.randomUUID();
        SearchResult context = new SearchResult(
                contextChunkId,
                documentId,
                "guide.md",
                "file://guide.md",
                "FILE",
                "text/markdown",
                2,
                "Section summary\nSection: Setup\nHeading path: Intro > Setup",
                java.util.Map.of(
                        "kind", "document_context",
                        "contextType", "section_summary",
                        "headingPath", "Intro > Setup",
                        "sectionTitle", "Setup"
                ),
                0.92
        );
        SearchResult original = new SearchResult(
                UUID.randomUUID(),
                documentId,
                "guide.md",
                "file://guide.md",
                "FILE",
                "text/markdown",
                0,
                "## Setup\nInstall the service and configure the database.",
                java.util.Map.of(
                        "headingPath", "Intro > Setup",
                        "sectionTitle", "Setup"
                ),
                0.0
        );

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), any(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(context),
                        new SearchService.SearchTiming(1, 1, 1, 0, 3, false, 1)
                ));
        when(documentRepository.contextRelatedChunks(any(), anyInt(), isNull(SearchFilter.class), any(), isNull()))
                .thenReturn(List.of(new DocumentRepository.ContextRelatedChunkCandidate(original, contextChunkId, "same_section", 0.92)));
        when(documentRepository.graphExpandedChunks(any(), anyInt(), anyInt(), any(), isNull()))
                .thenReturn(List.of());
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The document covers setup, including installation and database configuration [1][2]."));

        AskResponse response = service.ask(question, null, "summary", List.of(UUID.randomUUID()), null);

        assertThat(response.evidence()).hasSize(2);
        assertThat(response.evidence()).anySatisfy(evidence ->
                assertThat(evidence.metadata()).containsEntry("contextRelatedExpanded", true)
                        .containsEntry("contextRelatedReason", "same_section"));
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("context-related chunks"));
    }

    @Test
    void recruitmentCautionQuestionFallsBackToStructuredGuidance() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        String question = "공직유관단체 채용시 유의사항이 뭐야?";

        UUID documentId = UUID.randomUUID();
        when(searchService.search(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull()))
                .thenReturn(List.of(
                        searchResult(documentId, UUID.randomUUID(), 0,
                                "공직유관단체 채용 관련 주요 유의사항.pdf",
                                "application/pdf",
                                "공개경쟁시험: 직원 신규채용시 불특정 다수인을 대상으로 공개경쟁시험으로 채용하는 것을 원칙으로 함. 응시자의 공평한 기회 보장을 위해 성별, 신체조건, 용모, 학력, 연령 등에 대한 불합리한 제한을 두어서는 아니 됨."),
                        searchResult(documentId, UUID.randomUUID(), 1,
                                "공직유관단체 채용 관련 주요 유의사항.pdf",
                                "application/pdf",
                                "공정채용 기본원칙: 직원을 모집·채용할 때 연령 등을 이유로 차별하여서는 아니되며, 임직원의 가족·친척 등을 대상으로 한 우대채용도 금지. 가족채용 제한 및 친인척 인원수 공개."),
                        searchResult(documentId, UUID.randomUUID(), 2,
                                "공직유관단체 채용 관련 주요 유의사항.pdf",
                                "application/pdf",
                                "사전협의 통보기한 단축 및 협의 내용 간소화, 공고기간 단축, 시험단계 축소, 시험위원 중 외부전문가 비율 조정 등은 예외 운영 근거를 확인해야 함.")
                ));
        when(ollamaClient.chatResult(anyString(), anyString())).thenReturn(chat("제"));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).contains("공직유관단체 채용");
        assertThat(response.answer()).contains("공개경쟁");
        assertThat(response.answer()).contains("친인척");
        assertThat(response.answer()).contains("사전협의");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.answer()).contains("[2]");
        assertThat(response.confidence()).isEqualTo("보통");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("LLM"));
    }

    @Test
    void fastProfileDoesNotEscalateToRewriteOrDocumentGraphWhenEvidenceIsWeak() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagPipelineService pipelineService = mock(RagPipelineService.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setDocumentAdjacentExpansionEnabled(false);
        properties.getDocument().getGraph().setEnabled(true);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties, pipelineService);
        String question = "What is the security policy?";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "security-policy.pdf",
                "application/pdf",
                "The policy requires MFA for administrators.");

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), any(), isNull(), eq("FAST")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(4, 5, 6, 0, 15, false, 1)
                ));
        when(pipelineService.assessDocuments(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new RagPipelineService.EvidenceAssessment(false, 1, 0.2, 1, 0.0, List.of("weak")));
        when(pipelineService.maxIterations()).thenReturn(2);
        when(pipelineService.assessAnswer(anyString(), anyInt(), eq(true), any()))
                .thenReturn(new RagPipelineService.AnswerAssessment(true, "ok"));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The policy requires MFA for administrators [1]."));

        AskResponse response = service.ask(question, null, "qa", "FAST", List.of(UUID.randomUUID()), null);

        verify(pipelineService, never()).buildQueryPlan(anyString(), any(), any());
        verify(documentRepository, never()).graphExpandedChunks(any(), anyInt(), any(), isNull());
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("requested=FAST").contains("effective=FAST"));
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("embedding=4ms").contains("vector=5ms").contains("keyword=6ms"));
    }

    @Test
    void conversationalAskKeepsPinnedDocumentEvidenceWhenSearchRanksHigher() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, new LearnBotProperties());
        UUID spaceId = UUID.randomUUID();
        UUID pinnedDocumentId = UUID.randomUUID();
        UUID pinnedChunkId = UUID.randomUUID();
        SearchResult pinned = searchResult(
                pinnedDocumentId,
                pinnedChunkId,
                4,
                "policy.pdf",
                "application/pdf",
                "The previous policy evidence requires MFA for administrators."
        );
        SearchResult generic = searchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                "generic.pdf",
                "application/pdf",
                "A higher ranked generic search result."
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "that document",
                List.of(),
                List.of(),
                List.of(new DocumentConversationAnchor(
                        pinnedChunkId,
                        pinnedDocumentId,
                        pinned.title(),
                        pinned.sourceUri(),
                        pinned.chunkIndex(),
                        null,
                        "Access",
                        "Security > Access",
                        "policy"
                )),
                true
        );

        when(documentRepository.findActiveChunksByIds(any(), isNull(SearchFilter.class), any(), isNull()))
                .thenReturn(List.of(pinned));
        when(searchService.searchDetailed(anyString(), isNull(SearchFilter.class), anyInt(), any(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(generic),
                        new SearchService.SearchTiming(1, 1, 1, 0, 3, false, 1)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("model unavailable"));

        AskResponse response = service.askConversational(
                "that document",
                context,
                null,
                "qa",
                "BALANCED",
                List.of(spaceId),
                null
        );

        assertThat(response.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.chunkId()).isEqualTo(pinnedChunkId);
            assertThat(evidence.metadata()).containsEntry("conversationPinned", true);
        });
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("pinned"));
    }

    @Test
    void previousAnswerExpansionUsesRequiredPinnedEvidenceBeforeNewSearch() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setDocumentAdjacentExpansionEnabled(false);
        properties.getDocument().getGraph().setEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        UUID spaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID requiredChunkId = UUID.randomUUID();
        SearchResult required = searchResult(
                documentId,
                requiredChunkId,
                2,
                "policy.pdf",
                "application/pdf",
                "The policy requires quarterly access reviews and exception approval."
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "more detail by item",
                List.of(),
                List.of(),
                List.of(new DocumentConversationAnchor(requiredChunkId, documentId, "policy.pdf", "file://policy.pdf", 2, null, "Access", "Security > Access", "policy")),
                true,
                ConversationIntent.PREVIOUS_ANSWER_EXPANSION,
                List.of(new PreviousAnswerItem("Access control", "Access control [1]", List.of(1), List.of(requiredChunkId))),
                List.of(requiredChunkId),
                List.of()
        );

        when(documentRepository.findActiveChunksByIds(any(), isNull(SearchFilter.class), any(), isNull()))
                .thenReturn(List.of(required));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("model unavailable"));

        AskResponse response = service.askConversational(
                "more detail by item",
                context,
                null,
                "qa",
                "BALANCED",
                List.of(spaceId),
                null
        );

        verify(searchService, never()).searchDetailed(anyString(), any(), anyInt(), any(), any(), anyString());
        assertThat(response.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.chunkId()).isEqualTo(requiredChunkId);
            assertThat(evidence.metadata()).containsEntry("conversationRequired", true);
            assertThat(evidence.metadata()).containsEntry("previousAnswerItem", "Access control");
        });
    }

    @Test
    void streamingKeepsVisibleAnswerWhenSelfCheckWouldFallback() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        String question = "What is the security policy?";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "security-policy.pdf",
                "application/pdf",
                "The security policy requires MFA for administrators.");

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("FAST")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.streamChat(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(
                        streamDelta("The streamed answer is useful but lacks a citation.", false),
                        streamDelta("", true)
                ));

        StringBuilder visible = new StringBuilder();
        AskResponse response = service.askStreaming(
                question,
                null,
                "qa",
                "FAST",
                null,
                null,
                new RagService.AnswerStreamSink() {
                    @Override
                    public void onEvidence(List<SearchResult> citations, List<com.learnbot.dto.AnswerEvidence> evidence) {
                    }

                    @Override
                    public void onDelta(String text) {
                        visible.append(text);
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                        visible.setLength(0);
                        visible.append(answer);
                    }
                }
        );

        assertThat(visible.toString()).isEqualTo("The streamed answer is useful but lacks a citation.");
        assertThat(response.answer()).isEqualTo("The streamed answer is useful but lacks a citation.");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("Streaming answer was kept"));
    }

    @Test
    void streamingContinuesWhenModelStopsByLength() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        String question = "Summarize the security policy.";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "security-policy.pdf",
                "application/pdf",
                "The security policy requires MFA for administrators and quarterly access reviews.");

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.streamChat(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(
                        streamDelta("The policy requires MFA for administrators [1].", false),
                        streamDelta("", "length", true)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(new OllamaClient.ChatResult(
                        "It also requires quarterly access reviews [1].",
                        "stop",
                        true,
                        10,
                        12,
                        "http://ollama:11434",
                        "qwen3:8b-q4_K_M",
                        "primary",
                        false
                ));

        StringBuilder visible = new StringBuilder();
        AskResponse response = service.askStreaming(
                question,
                null,
                "qa",
                "BALANCED",
                null,
                null,
                new RagService.AnswerStreamSink() {
                    @Override
                    public void onEvidence(List<SearchResult> citations, List<com.learnbot.dto.AnswerEvidence> evidence) {
                    }

                    @Override
                    public void onDelta(String text) {
                        visible.append(text);
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                        visible.setLength(0);
                        visible.append(answer);
                    }
                }
        );

        assertThat(response.answer()).contains("MFA for administrators [1]");
        assertThat(response.answer()).contains("quarterly access reviews [1]");
        assertThat(visible.toString()).isEqualTo(response.answer());
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("automatically continued"));
    }

    @Test
    void diagnosticsReportInvalidDocumentCitationReference() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        String question = "What is the security policy?";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "security-policy.pdf",
                "application/pdf",
                "The security policy requires MFA for administrators.");

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The security policy requires MFA for administrators [2]."));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).contains("[2]");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("RAG quality trace").contains("invalidCitationRefs=1"));
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("Citation support").contains("outside returned evidence"));
    }

    @Test
    void streamingUsesCompactPromptWithoutChangingVisibleEvidence() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        String question = "What benefits are available?";
        String longSourceUri = "https://example.com/docs/" + "very-long-path/".repeat(30) + "benefits-policy.html";
        UUID hiddenChunkId = UUID.randomUUID();
        List<SearchResult> results = List.of(
                searchResult(UUID.randomUUID(), UUID.randomUUID(), 0, "benefits-a.pdf", "application/pdf", "Health check support and vacation support are available."),
                searchResult(UUID.randomUUID(), UUID.randomUUID(), 1, "benefits-b.pdf", "application/pdf", "Education support is available for eligible employees."),
                searchResult(UUID.randomUUID(), UUID.randomUUID(), 2, "benefits-c.pdf", "application/pdf", "Family event support is available under the policy."),
                searchResult(UUID.randomUUID(), UUID.randomUUID(), 3, "benefits-d.pdf", "application/pdf", "Meal support is described in the benefits policy."),
                searchResultWithSource(UUID.randomUUID(), hiddenChunkId, 4, "benefits-e.pdf", longSourceUri, "application/pdf", "Commuting support is available for approved cases."),
                searchResult(UUID.randomUUID(), UUID.randomUUID(), 5, "benefits-f.pdf", "application/pdf", "Wellness support is available through the program.")
        );

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        results,
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.streamChat(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(streamDelta("Benefits include health checks and education support [1][2].", false), streamDelta("", true)));

        StringBuilder statuses = new StringBuilder();
        AskResponse response = service.askStreaming(
                question,
                null,
                "qa",
                "BALANCED",
                null,
                null,
                new RagService.AnswerStreamSink() {
                    @Override
                    public void onStatus(String stage, String message) {
                        statuses.append(stage).append("|");
                    }

                    @Override
                    public void onEvidence(List<SearchResult> citations, List<com.learnbot.dto.AnswerEvidence> evidence) {
                    }

                    @Override
                    public void onDelta(String text) {
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                    }
                }
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).streamChat(anyString(), promptCaptor.capture(), anyInt());
        assertThat(promptCaptor.getValue()).contains("Key excerpt:");
        assertThat(promptCaptor.getValue()).doesNotContain(longSourceUri, hiddenChunkId.toString());
        assertThat(response.evidence()).hasSize(6);
        assertThat(statuses.toString()).contains("retrieval_started|", "evidence_ready|", "llm_started|");
    }

    @Test
    void streamingKeepsRequiredConversationEvidenceDetailed() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        UUID requiredChunkId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        SearchResult required = searchResult(
                UUID.randomUUID(),
                requiredChunkId,
                42,
                "access-control.pdf",
                "application/pdf",
                "Required pinned evidence states that administrators must use MFA before approving access.",
                Map.of("conversationRequired", true, "previousAnswerItem", "Access control")
        );
        RagConversationContext context = new RagConversationContext(
                UUID.randomUUID(),
                "expand that item",
                List.of(),
                List.of(),
                List.of(new DocumentConversationAnchor(requiredChunkId, required.documentId(), "access-control.pdf", "file://access-control.pdf", 42, null, "Access control", "Security > Access", "policy")),
                true,
                ConversationIntent.PREVIOUS_ANSWER_EXPANSION,
                List.of(new PreviousAnswerItem("Access control", "summary [1]", List.of(1), List.of(requiredChunkId))),
                List.of(requiredChunkId),
                List.of()
        );

        when(documentRepository.findActiveChunksByIds(any(), isNull(SearchFilter.class), any(), isNull()))
                .thenReturn(List.of(required));
        when(ollamaClient.streamChat(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(streamDelta("Administrators must use MFA before approving access [1].", false), streamDelta("", true)));

        AskResponse response = service.askConversationalStreaming(
                "expand that item",
                context,
                null,
                "qa",
                "BALANCED",
                List.of(spaceId),
                null,
                new NoopDocumentStreamSink()
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).streamChat(anyString(), promptCaptor.capture(), anyInt());
        assertThat(promptCaptor.getValue()).contains("Required pinned evidence states that administrators must use MFA");
        assertThat(response.evidence()).anySatisfy(evidence -> assertThat(evidence.metadata()).containsEntry("conversationRequired", true));
    }

    @Test
    void streamingQuoteModePreservesLongerSourceExcerpt() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        String question = "Quote the exact exception clause.";
        String importantTail = "The exact exception clause says emergency approval is allowed only when the incident commander records the reason.";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "exception-policy.pdf",
                "application/pdf",
                "Background. ".repeat(80) + importantTail);

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.streamChat(anyString(), anyString(), anyInt()))
                .thenReturn(Flux.just(streamDelta("Emergency approval requires recording the reason [1].", false), streamDelta("", true)));

        service.askStreaming(question, null, "quote", "BALANCED", null, null, new NoopDocumentStreamSink());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient).streamChat(anyString(), promptCaptor.capture(), anyInt());
        assertThat(promptCaptor.getValue()).contains(importantTail);
    }

    @Test
    void nonStreamingAnswerMissingCitationIsNotReplacedWhenOtherwiseUseful() {
        SearchService searchService = mock(SearchService.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setRewriteEnabled(false);
        RagService service = new RagService(searchService, ollamaClient, documentRepository, properties);
        String question = "What is the security policy?";
        SearchResult result = searchResult(UUID.randomUUID(), UUID.randomUUID(), 0,
                "security-policy.pdf",
                "application/pdf",
                "The security policy requires MFA for administrators.");

        when(searchService.searchDetailed(eq(question), isNull(SearchFilter.class), anyInt(), isNull(), isNull(), eq("BALANCED")))
                .thenReturn(new SearchService.SearchResponse(
                        List.of(result),
                        new SearchService.SearchTiming(1, 1, 1, 0, 1, false, 1)
                ));
        when(ollamaClient.chatResult(anyString(), anyString(), anyInt()))
                .thenReturn(chat("The security policy requires MFA for administrators."));

        AskResponse response = service.ask(question, null, "qa");

        assertThat(response.answer()).isEqualTo("The security policy requires MFA for administrators.");
    }

    private SearchResult searchResult(UUID documentId, UUID chunkId, int chunkIndex, String title) {
        return searchResult(
                documentId,
                chunkId,
                chunkIndex,
                title,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "Sheet Sheet1 Row 1: C2=연번 | C3=직종 | C4=이름"
        );
    }

    private static OllamaClient.ChatResult chat(String content) {
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
    }

    private static OllamaClient.ChatStreamDelta streamDelta(String content, boolean done) {
        return new OllamaClient.ChatStreamDelta(content, done ? "stop" : null, done, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
    }

    private static OllamaClient.ChatStreamDelta streamDelta(String content, String doneReason, boolean done) {
        return new OllamaClient.ChatStreamDelta(content, doneReason, done, 0, 0, "http://ollama:11434", "qwen3:8b-q4_K_M", "primary", false);
    }

    private SearchResult searchResult(UUID documentId, UUID chunkId, int chunkIndex, String title, String contentType, String content) {
        return searchResult(documentId, chunkId, chunkIndex, title, "file://" + title, contentType, content, Map.of());
    }

    private SearchResult searchResult(UUID documentId, UUID chunkId, int chunkIndex, String title, String contentType, String content, Map<String, Object> metadata) {
        return searchResult(documentId, chunkId, chunkIndex, title, "file://" + title, contentType, content, metadata);
    }

    private SearchResult searchResultWithSource(UUID documentId, UUID chunkId, int chunkIndex, String title, String sourceUri, String contentType, String content) {
        return searchResult(documentId, chunkId, chunkIndex, title, sourceUri, contentType, content, Map.of());
    }

    private SearchResult searchResult(UUID documentId, UUID chunkId, int chunkIndex, String title, String sourceUri, String contentType, String content, Map<String, Object> metadata) {
        return new SearchResult(
                chunkId,
                documentId,
                title,
                sourceUri,
                "FILE",
                contentType,
                chunkIndex,
                content,
                metadata,
                0.9
        );
    }

    private DocumentChunkDetail chunk(int chunkIndex, String content) {
        return new DocumentChunkDetail(UUID.randomUUID(), chunkIndex, content, OffsetDateTime.now());
    }

    private static class NoopDocumentStreamSink implements RagService.AnswerStreamSink {
        @Override
        public void onEvidence(List<SearchResult> citations, List<com.learnbot.dto.AnswerEvidence> evidence) {
        }

        @Override
        public void onDelta(String text) {
        }

        @Override
        public void onReplace(String answer, String reason) {
        }
    }
}
