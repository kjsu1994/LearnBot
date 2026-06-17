package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.repository.DocumentRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

        assertThat(response.answer()).contains("개선되는 핵심");
        assertThat(response.answer()).contains("임금");
        assertThat(response.answer()).contains("[1]");
        assertThat(response.confidence()).isIn("높음", "보통");
        assertThat(response.diagnostics()).anySatisfy(note -> assertThat(note).contains("검색 근거 기반 답변으로 대체"));
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
        return new OllamaClient.ChatResult(content, "stop", true, 0, 0);
    }

    private SearchResult searchResult(UUID documentId, UUID chunkId, int chunkIndex, String title, String contentType, String content) {
        return new SearchResult(
                chunkId,
                documentId,
                title,
                "file://" + title,
                "FILE",
                contentType,
                chunkIndex,
                content,
                0.9
        );
    }

    private DocumentChunkDetail chunk(int chunkIndex, String content) {
        return new DocumentChunkDetail(UUID.randomUUID(), chunkIndex, content, OffsetDateTime.now());
    }
}
