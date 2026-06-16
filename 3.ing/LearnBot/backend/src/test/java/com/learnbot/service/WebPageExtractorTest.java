package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPageExtractorTest {
    @Test
    void rejectsDomainsOutsideAllowList() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCrawler().setAllowedDomains(java.util.List.of("example.com"));
        WebPageExtractor extractor = new WebPageExtractor(properties);

        assertThatThrownBy(() -> extractor.extract("https://not-allowed.test/page"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Domain is not allowed");
    }
}
