package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.repository.DocumentRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebPageExtractorTest {
    @Test
    void rejectsDomainsOutsideAllowList() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setAllowedDomains(java.util.List.of("example.com"));
        DocumentRepository repository = mock(DocumentRepository.class);
        WebPageExtractor extractor = new WebPageExtractor(properties, repository);

        assertThatThrownBy(() -> extractor.extract("https://not-allowed.test/page"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Domain is not allowed");

        verify(repository).createCrawlAuditLog(
                isNull(),
                any(),
                any(),
                anyBoolean(),
                isNull(),
                isNull(),
                anyBoolean(),
                any()
        );
    }
}
