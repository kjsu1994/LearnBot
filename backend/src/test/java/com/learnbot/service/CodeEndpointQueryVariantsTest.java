package com.learnbot.service;

import org.junit.jupiter.api.Test;

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
}
