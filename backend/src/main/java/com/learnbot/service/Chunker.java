package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class Chunker {
    private final LearnBotProperties properties;

    public Chunker(LearnBotProperties properties) {
        this.properties = properties;
    }

    public List<Chunk> split(String content) {
        String normalized = normalize(content);
        int size = properties.getChunking().getSize();
        int overlap = Math.min(properties.getChunking().getOverlap(), size - 1);
        List<Chunk> chunks = new ArrayList<>();

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            if (end < normalized.length()) {
                int boundary = normalized.lastIndexOf('\n', end);
                if (boundary > start + size / 2) {
                    end = boundary;
                }
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(new Chunk(chunks.size(), chunk, Map.of("start", start, "end", end)));
            }

            if (end == normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }

        return chunks;
    }

    private String normalize(String content) {
        return content == null ? "" : content
                .replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }
}
