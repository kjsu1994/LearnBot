package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {
    @Test
    void splitCreatesOverlappingChunks() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getChunking().setSize(300);
        properties.getChunking().setOverlap(30);
        Chunker chunker = new Chunker(properties);

        String content = "a".repeat(800);
        List<Chunk> chunks = chunker.split(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).index()).isZero();
    }
}
