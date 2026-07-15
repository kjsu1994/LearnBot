package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeNavigationHandle;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExtractorsTest {
    @Test
    void endpointExtractorProducesNormalizedExactFactsWithoutChangingSearchResult() {
        CodeSearchResult source = result("src/Api.java", "submit", "return service.submit();", Map.of(
                "indexVersion", "v7",
                "endpointRoute", "api//orders/{id}?preview=true",
                "httpMethod", "post",
                "graphRelation", "EXPOSES_ENDPOINT",
                "codeIntelligenceAuthority", "COMPILER_SEMANTIC"));

        CodeEvidenceIr ir = new EndpointEvidenceExtractor().extract(context(source));

        assertThat(ir.facts()).anySatisfy(fact -> {
            assertThat(fact.predicate()).isEqualTo("EXPOSES_ENDPOINT");
            assertThat(fact.value()).isEqualTo("/api/orders/{id}");
            assertThat(fact.authority().name()).isEqualTo("COMPILER_SEMANTIC");
        }).anySatisfy(fact -> {
            assertThat(fact.predicate()).isEqualTo("HTTP_METHOD");
            assertThat(fact.value()).isEqualTo("POST");
        });
        assertThat(ir.constraints()).extracting(CodeEvidenceConstraint::type)
                .contains(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED);
        assertThat(ir.evidenceItems().get(0).source()).isSameAs(source);
        assertThat(source.score()).isEqualTo(0.42);
    }

    @Test
    void assignmentExtractorKeepsLiteralAssignmentsAndMarksTransitionCandidate() {
        CodeSearchResult source = result("src/Worker.java", "run", """
                state.ready = false;
                execute();
                state.ready = true;
                """, Map.of("indexVersion", "v7"));

        CodeEvidenceIr ir = new AssignmentEvidenceExtractor().extract(context(source));

        assertThat(ir.facts()).filteredOn(fact -> fact.predicate().equals("ASSIGNS_LITERAL"))
                .extracting(fact -> fact.subject() + "=" + fact.value())
                .containsExactly("state.ready=false", "state.ready=true");
        assertThat(ir.facts()).anySatisfy(fact -> {
            assertThat(fact.predicate()).isEqualTo("STATE_TRANSITION_CANDIDATE");
            assertThat(fact.value()).isEqualTo("false -> true");
        });
        assertThat(ir.constraints()).filteredOn(value -> value.type() == CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED)
                .hasSize(2);
        assertThat(ir.evidenceItems().get(0).authority().name()).isEqualTo("SYNTAX");
    }

    @Test
    void transactionExtractorRequiresDirectProofWhenAnnotationProvenanceIsMissing() {
        CodeSearchResult source = result("src/UnitOfWork.java", "save", "saveChanges();", Map.of(
                "graphEdgeTypes", List.of("CALLS", "TRANSACTION_BOUNDARY"),
                "codeIntelligenceAuthority", "SCIP_SEMANTIC"));

        CodeEvidenceIr ir = new TransactionEvidenceExtractor().extract(context(source));

        assertThat(ir.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.predicate()).isEqualTo("TRANSACTION_BOUNDARY");
            assertThat(fact.authority().name()).isEqualTo("SCIP_SEMANTIC");
        });
        assertThat(ir.constraints()).singleElement().satisfies(constraint ->
                assertThat(constraint.type()).isEqualTo(CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED));
    }

    @Test
    void navigationExtractorEmitsBoundedHandlesThatCannotProveBehaviorAlone() {
        CodeSearchResult source = result("src/Caller.java", "call", """
                gateway.dispatch(request);
                return new ResultView();
                """, Map.of());

        CodeEvidenceIr ir = new NavigationEvidenceExtractor().extract(
                new CodeEvidenceExtractionContext("follow the call", EvidenceExtractionStage.POST_OPERATION,
                        List.of(source), 2));

        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::kind)
                .containsExactly(CodeNavigationHandle.Kind.CALL, CodeNavigationHandle.Kind.TYPE);
        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .containsExactly("gateway.dispatch", "ResultView");
        assertThat(ir.constraints()).hasSize(2).allSatisfy(constraint ->
                assertThat(constraint.type()).isEqualTo(CodeEvidenceConstraint.Type.NAVIGATION_ONLY));
    }

    @Test
    void persistenceExtractorConvertsRelationsAndDeclaredQueryToTypedFacts() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("graphEdgeType", "QUERIES_ENTITY");
        metadata.put("graphPathNodes", List.of("OrderRepository.findOpen", "Order"));
        metadata.put("declaredQuery", "select o from Order o where o.closed = false");
        metadata.put("codeIntelligenceAuthority", "LSP_SEMANTIC");
        CodeSearchResult source = result("src/OrderRepository.java", "findOpen", "", metadata);

        CodeEvidenceIr ir = new PersistenceEvidenceExtractor().extract(context(source));

        assertThat(ir.facts()).anySatisfy(fact -> {
            assertThat(fact.predicate()).isEqualTo("QUERIES_ENTITY");
            assertThat(fact.value()).isEqualTo("Order");
        }).anySatisfy(fact -> {
            assertThat(fact.predicate()).isEqualTo("DECLARES_QUERY");
            assertThat(fact.value()).contains("closed = false");
        });
        assertThat(ir.evidenceItems()).singleElement().satisfies(item ->
                assertThat(item.kinds()).contains(CodeEvidenceItem.Kind.PERSISTENCE,
                        CodeEvidenceItem.Kind.GRAPH_RELATION));
    }

    private CodeEvidenceExtractionContext context(CodeSearchResult source) {
        return new CodeEvidenceExtractionContext("question", EvidenceExtractionStage.POST_OPERATION,
                List.of(source));
    }

    private CodeSearchResult result(String path, String method, String content, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repository", path,
                "method", method, "SampleType", method, "sample", null, null,
                0, 10, 40, content, 0.42, metadata);
    }
}
