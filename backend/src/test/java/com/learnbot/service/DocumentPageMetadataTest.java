package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPageMetadataTest {
    @Test
    void usesExplicitPageNumberWhenPresent() {
        assertThat(DocumentPageMetadata.canonicalPageNumber(Map.of("pageNumber", 7, "pageStart", 1, "pageEnd", 3)))
                .isEqualTo(7);
    }

    @Test
    void derivesPageNumberOnlyForSinglePageRange() {
        assertThat(DocumentPageMetadata.canonicalPageNumber(Map.of("pageStart", 3, "pageEnd", 3)))
                .isEqualTo(3);
        assertThat(DocumentPageMetadata.canonicalPageNumber(Map.of("pageStart", 3, "pageEnd", 4)))
                .isNull();
    }
}
