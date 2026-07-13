package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceFileDiversityTest {
    @Test
    void reservesRoomForRelevantEvidenceFromOtherFiles() {
        List<CodeSearchResult> ranked = new ArrayList<>();
        for (int index = 0; index < 10; index++) ranked.add(result("src/PrimaryService.java", "method" + index, Map.of()));
        ranked.add(result("src/Repository.java", "load", Map.of()));
        ranked.add(result("src/ModelClient.java", "chat", Map.of()));

        List<CodeSearchResult> output = CodeEvidenceFileDiversity.select(
                ranked, ranked.subList(0, 8), 8, ignored -> false);

        assertThat(output).extracting(CodeSearchResult::filePath)
                .contains("src/Repository.java", "src/ModelClient.java");
        assertThat(output).hasSize(8);
    }

    @Test
    void fillsSingleFileSlateAndAlwaysKeepsRequiredEvidence() {
        List<CodeSearchResult> ranked = new ArrayList<>();
        for (int index = 0; index < 8; index++) ranked.add(result("src/OnlyFile.cs", "method" + index, Map.of()));
        CodeSearchResult required = result("src/Endpoint.java", "ask", Map.of("required", true));
        ranked.add(required);

        List<CodeSearchResult> output = CodeEvidenceFileDiversity.select(
                ranked, ranked.subList(0, 8), 8,
                result -> Boolean.TRUE.equals(result.metadata().get("required")));

        assertThat(output).hasSize(8).contains(required);
    }

    private CodeSearchResult result(String path, String method, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, "Type", method, "app", null, null, 1,
                1, 12, "void " + method + "() {}", 0.8, metadata);
    }
}
