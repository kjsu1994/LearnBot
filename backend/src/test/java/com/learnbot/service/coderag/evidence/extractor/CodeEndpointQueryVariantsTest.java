package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.service.RagPipelineService;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEndpointQueryVariantsTest {
    @Test
    void expandsACompoundRouteWithoutFrameworkSpecificNames() {
        assertThat(CodeEndpointQueryVariants.expand("explain /api/code/ask flow"))
                .contains("explain /api/code/ask flow", "api code ask", "/ask", "/api/code");
    }

    @Test
    void leavesOrdinaryQueriesUnchanged() {
        assertThat(CodeEndpointQueryVariants.expand("CodeRagService implementation"))
                .containsExactly("CodeRagService implementation");
    }

    @Test
    void endpointLookupIsATypedSearchOperation() {
        var operation = new RagPipelineService.CodeSearchOperation(
                "find_endpoint", "/api/items/{id}", "entry", "request_entry");

        assertThat(operation.isSearch()).isTrue();
        assertThat(operation.validationError()).isEmpty();
    }

    @Test
    void ranksEndpointInventoryByRouteSymbolPathAndSourceCoverage() {
        CodeSearchResult genericRag = endpoint(
                "src/web/RagController.java", "RagController", "ask", "/api/rag/ask",
                "return ragService.ask(request.question());");
        CodeSearchResult codeRag = endpoint(
                "src/web/CodeController.java", "CodeController", "ask", "/api/code/ask",
                "return codeRagService.askConversational(request.question());");

        assertThat(CodeEndpointQueryVariants.rankCandidates(
                "Which controller handles the Code RAG ask API and which service call does it make?",
                List.of(genericRag, codeRag), 2))
                .extracting(CodeSearchResult::filePath)
                .containsExactly("src/web/CodeController.java", "src/web/RagController.java");
    }

    private CodeSearchResult endpoint(String path, String className, String method, String route, String content) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", method, className, method, "sample", null, null, 1,
                10, 30, content, 0.95, Map.of("endpointRoute", route));
    }
}
