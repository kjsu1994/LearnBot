package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.SearchResult;
import com.learnbot.repository.DocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {
    @Test
    void reranksRelevantDocumentAboveHigherVectorNoise() {
        DocumentRepository repository = mock(DocumentRepository.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        SearchService service = new SearchService(repository, ollamaClient);
        String question = "기간제 단시간 파견 근로자 차별예방을 위해 뭐가 개선되는거야?";
        SearchResult noisy = result(
                "일반 근로계약 안내.pdf",
                "근로계약서 작성 방식과 서명 절차를 설명합니다.",
                0.86
        );
        SearchResult relevant = result(
                "기간제·단시간·파견 근로자 차별 예방 가이드라인.pdf",
                "차별 예방을 위해 임금, 복리후생, 교육훈련, 고충처리 절차의 불합리한 차이를 개선합니다.",
                0.62
        );

        when(ollamaClient.embed(anyList())).thenReturn(List.of(List.of(0.1)));
        when(repository.search(anyString(), anyList(), isNull(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(noisy, relevant));
        when(repository.keywordSearch(anyString(), isNull(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of());

        List<SearchResult> results = service.search(question, null, 2, List.of(UUID.randomUUID()), null);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).contains("차별 예방");
    }

    @Test
    void expandsDiscriminationQuestionWithDomainTerms() {
        SearchService service = new SearchService(null, null);

        List<String> expanded = service.expandedQueries("차별예방을 위해 뭐가 개선되는거야?");

        assertThat(expanded)
                .contains("차별 예방 개선")
                .anySatisfy(query -> assertThat(query).contains("임금").contains("복리후생"));
    }

    @Test
    void cachesQueryEmbeddingUntilTtlExpires() {
        DocumentRepository repository = mock(DocumentRepository.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getPipeline().setQueryEmbeddingCacheTtlSeconds(3600);
        SearchService service = new SearchService(repository, ollamaClient, null, properties);

        when(ollamaClient.embed(anyList())).thenReturn(List.of(List.of(0.1)));
        when(repository.search(anyString(), anyList(), isNull(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of(result("policy.pdf", "security policy", 0.8)));
        when(repository.keywordSearch(anyString(), isNull(), anyInt(), anyList(), isNull()))
                .thenReturn(List.of());

        SearchService.SearchResponse first = service.searchDetailed("security policy", null, 2, List.of(UUID.randomUUID()), null, "BALANCED");
        SearchService.SearchResponse second = service.searchDetailed("security policy", null, 2, List.of(UUID.randomUUID()), null, "BALANCED");

        verify(ollamaClient, times(1)).embed(anyList());
        assertThat(first.timing().embeddingCacheHit()).isFalse();
        assertThat(second.timing().embeddingCacheHit()).isTrue();
    }

    private SearchResult result(String title, String content, double score) {
        return new SearchResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                title,
                "file://" + title,
                "FILE",
                "application/pdf",
                1,
                content,
                score
        );
    }
}
