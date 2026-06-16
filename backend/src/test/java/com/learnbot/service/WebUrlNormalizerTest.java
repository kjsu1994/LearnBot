package com.learnbot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebUrlNormalizerTest {
    private final WebUrlNormalizer normalizer = new WebUrlNormalizer();

    @Test
    void addsHttpsWhenSchemeIsMissing() {
        assertThat(normalizer.normalize("example.com/docs"))
                .isEqualTo("https://example.com/docs");
    }

    @Test
    void preservesHttpSchemeAndQuery() {
        assertThat(normalizer.normalize("http://Example.com:8080/docs?q=rag"))
                .isEqualTo("http://example.com:8080/docs?q=rag");
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertThatThrownBy(() -> normalizer.normalize("https://"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("호스트");
    }
}
