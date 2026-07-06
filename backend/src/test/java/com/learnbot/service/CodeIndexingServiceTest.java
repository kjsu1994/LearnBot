package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeIndexingServiceTest {

    @Test
    void contentHashMatchDoesNotReuseLegacyParserChunks() {
        ActiveCodeFileSnapshot legacy = new ActiveCodeFileSnapshot(
                UUID.randomUUID(),
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "same-hash",
                12,
                "legacy",
                "legacy"
        );

        assertThat(CodeIndexingService.canReusePreviousSnapshot(legacy, "same-hash")).isFalse();
    }

    @Test
    void contentHashAndCurrentParserSignatureReusePreviousChunks() {
        ActiveCodeFileSnapshot current = new ActiveCodeFileSnapshot(
                UUID.randomUUID(),
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "same-hash",
                12,
                CodeIndexingService.CODE_PARSER_SIGNATURE,
                CodeIndexingService.CODE_CHUNK_PROFILE
        );

        assertThat(CodeIndexingService.canReusePreviousSnapshot(current, "same-hash")).isTrue();
    }

    @Test
    void currentParserSignatureDoesNotReuseWhenContentChanged() {
        ActiveCodeFileSnapshot current = new ActiveCodeFileSnapshot(
                UUID.randomUUID(),
                "backend/src/main/java/app/service/RagAnswerPipeline.java",
                "old-hash",
                12,
                CodeIndexingService.CODE_PARSER_SIGNATURE,
                CodeIndexingService.CODE_CHUNK_PROFILE
        );

        assertThat(CodeIndexingService.canReusePreviousSnapshot(current, "new-hash")).isFalse();
    }
}
