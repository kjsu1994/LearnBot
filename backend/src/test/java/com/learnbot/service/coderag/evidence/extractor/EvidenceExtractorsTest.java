package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeNavigationHandle;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExtractorsTest {
    @Test
    void endpointExtractorKeepsIncidentalEndpointAsStructureWithoutForcingIt() {
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
        assertThat(ir.constraints()).noneMatch(value ->
                value.type() == CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED);
        assertThat(ir.evidenceItems().get(0).source()).isSameAs(source);
        assertThat(source.score()).isEqualTo(0.42);
    }

    @Test
    void endpointExtractorRequiresOnlyTheSingleExplicitRouteMatch() {
        CodeSearchResult requested = result("src/OrderApi.java", "submit", "return service.submit();", Map.of(
                "endpointRoute", "/api/orders/{id}", "httpMethod", "post"));
        CodeSearchResult incidental = result("src/AdminApi.java", "submit", "return admin.submit();", Map.of(
                "endpointRoute", "/api/admin/orders", "httpMethod", "post"));
        CodeEvidenceExtractionContext context = new CodeEvidenceExtractionContext(
                "Which handler serves /api/orders/{id}?", EvidenceExtractionStage.POST_OPERATION,
                List.of(incidental, requested));

        CodeEvidenceIr ir = new EndpointEvidenceExtractor().extract(context);

        List<String> requiredFactIds = ir.constraints().stream()
                .filter(value -> value.type() == CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED)
                .map(CodeEvidenceConstraint::targetId)
                .toList();
        assertThat(requiredFactIds).singleElement();
        assertThat(ir.facts()).filteredOn(fact -> requiredFactIds.contains(fact.factId()))
                .extracting(CodeEvidenceFact::value)
                .containsExactly("/api/orders/{id}");
        assertThat(com.learnbot.service.coderag.answer.CodeEvidenceIrFidelity.promptFacts(ir))
                .contains("Trusted typed facts from selected source evidence. Preserve required values exactly:")
                .contains("`SampleType.submit: EXPOSES_ENDPOINT=/api/orders/{id}`");
    }

    @Test
    void endpointExtractorUsesUnambiguousFindEndpointOperationProvenance() {
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "find_endpoint", "find-entry", List.of("claim-entry"), "request_entry");
        CodeSearchResult source = result("src/OrderApi.java", "submit", "return service.submit();", Map.of(
                "endpointRoute", "/api/orders/{id}",
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance)));
        CodeEvidenceExtractionContext context = new CodeEvidenceExtractionContext(
                "Where is order submission handled?", EvidenceExtractionStage.POST_OPERATION, List.of(source));

        CodeEvidenceIr ir = new EndpointEvidenceExtractor().extract(context);

        assertThat(ir.constraints()).filteredOn(value ->
                value.type() == CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED).hasSize(1);
    }

    @Test
    void endpointExtractorDoesNotChooseTheFirstOfMultipleFindEndpointRoutes() {
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "find_endpoint", "find-entry", List.of("claim-entry"), "request_entry");
        CodeSearchResult orders = result("src/OrderApi.java", "submit", "", Map.of(
                "endpointRoute", "/api/orders",
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance)));
        CodeSearchResult drafts = result("src/DraftApi.java", "submit", "", Map.of(
                "endpointRoute", "/api/drafts",
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance)));
        CodeEvidenceExtractionContext context = new CodeEvidenceExtractionContext(
                "Where is submission handled?", EvidenceExtractionStage.POST_OPERATION,
                List.of(orders, drafts));

        CodeEvidenceIr ir = new EndpointEvidenceExtractor().extract(context);

        assertThat(ir.constraints()).noneMatch(value ->
                value.type() == CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED);
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
    void assignmentExtractorCapturesBoundedMultilineExpressionWithoutChangingLiteralTransitions() {
        CodeSearchResult source = result("src/Renderer.java", "refresh", """
                renderState.visible = false;
                renderState.bounds =
                        new Region(
                                source.left - inset,
                                source.top - inset,
                                source.width + inset * 2,
                                source.height + inset * 2
                        );
                renderState.visible = true;
                """, Map.of("indexVersion", "v8"));
        AssignmentEvidenceExtractor extractor = new AssignmentEvidenceExtractor();

        CodeEvidenceIr ir = extractor.extract(context(source));

        assertThat(extractor.supports(context(source))).isTrue();
        assertThat(ir.facts()).filteredOn(fact -> fact.predicate().equals("ASSIGNS_LITERAL"))
                .extracting(fact -> fact.subject() + "=" + fact.value())
                .containsExactly("renderState.visible=false", "renderState.visible=true");
        CodeEvidenceFact expression = ir.facts().stream()
                .filter(fact -> fact.predicate().equals("ASSIGNS_EXPRESSION"))
                .findFirst()
                .orElseThrow();
        assertThat(expression.subject()).isEqualTo("renderState.bounds");
        assertThat(expression.value())
                .startsWith("new Region(")
                .contains("source.left - inset", "source.height + inset * 2")
                .endsWith(")");
        assertThat(expression.exactness()).isEqualTo(CodeEvidenceFact.Exactness.EXACT);
        assertThat(ir.facts()).anySatisfy(fact -> {
            assertThat(fact.predicate()).isEqualTo("STATE_TRANSITION_CANDIDATE");
            assertThat(fact.value()).isEqualTo("false -> true");
        });
    }

    @Test
    void assignmentExtractorRejectsExpressionBeyondTheBoundedLineWindow() {
        String expression = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> "        part" + index + ",")
                .reduce("renderState.bounds = compose(\n", (left, right) -> left + right + "\n")
                + ");";
        CodeSearchResult source = result("src/Renderer.java", "refresh", expression, Map.of());
        AssignmentEvidenceExtractor extractor = new AssignmentEvidenceExtractor();

        CodeEvidenceIr ir = extractor.extract(context(source));

        assertThat(extractor.supports(context(source))).isFalse();
        assertThat(ir.facts()).noneMatch(fact -> fact.predicate().equals("ASSIGNS_EXPRESSION"));
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
                        List.of(source), 3));

        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::kind)
                .containsExactly(CodeNavigationHandle.Kind.DEFINITION,
                        CodeNavigationHandle.Kind.CALL, CodeNavigationHandle.Kind.TYPE);
        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .containsExactly("call", "gateway.dispatch", "ResultView");
        assertThat(ir.facts()).extracting(CodeEvidenceFact::predicate)
                .containsExactly("CALLS_SYMBOL", "CONSTRUCTS_TYPE");
        assertThat(ir.facts()).extracting(CodeEvidenceFact::subject)
                .containsOnly("SampleType.call");
        assertThat(ir.facts()).extracting(CodeEvidenceFact::value)
                .containsExactly("gateway.dispatch", "ResultView");
        assertThat(ir.constraints()).hasSize(3).allSatisfy(constraint ->
                assertThat(constraint.type()).isEqualTo(CodeEvidenceConstraint.Type.NAVIGATION_ONLY));
    }

    @Test
    void navigationExtractorExposesCallableChunkAsGraphSeedWithoutParsingItsLanguage() {
        CodeSearchResult source = result("src/Worker.cs", "Handle", "StartWork();", Map.of());

        CodeEvidenceIr ir = new NavigationEvidenceExtractor().extract(
                new CodeEvidenceExtractionContext("follow the lifecycle", EvidenceExtractionStage.POST_OPERATION,
                        List.of(source), 2));

        assertThat(ir.navigationHandles()).first().satisfies(handle -> {
            assertThat(handle.kind()).isEqualTo(CodeNavigationHandle.Kind.DEFINITION);
            assertThat(handle.symbol()).isEqualTo("Handle");
            assertThat(handle.chunkId()).isEqualTo(source.chunkId());
            assertThat(handle.sourceEvidenceId()).isEqualTo(CodeEvidenceItem.evidenceId(source));
        });
        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .containsExactly("Handle", "StartWork");
        assertThat(ir.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.predicate()).isEqualTo("CALLS_SYMBOL");
            assertThat(fact.value()).isEqualTo("StartWork");
        });
    }

    @Test
    void navigationExtractorFindsSameTypeCallsButIgnoresDeclarationsCommentsAndStrings() {
        CodeSearchResult source = result("src/Flow.java", "run", """
                void run() {
                    // ignoredCall();
                    String sample = "alsoIgnored()";
                    retrieveEvidence();
                    if (ready()) {
                        generateAnswer();
                    }
                }
                """, Map.of());

        CodeEvidenceIr ir = new NavigationEvidenceExtractor().extract(
                new CodeEvidenceExtractionContext("trace the flow", EvidenceExtractionStage.POST_OPERATION,
                        List.of(source), 8));

        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .containsExactly("run", "retrieveEvidence", "ready", "generateAnswer")
                .doesNotContain("ignoredCall", "alsoIgnored", "if");
    }

    @Test
    void navigationExtractorKeepsQualifiedAndSameScopeCallsInObservedSourceOrder() {
        CodeSearchResult source = result("src/Flow.cs", "Run", """
                void Run() {
                    first.Receive();
                    Transform();
                    second.Publish();
                    Complete();
                }
                """, Map.of());

        CodeEvidenceIr ir = new NavigationEvidenceExtractor().extract(
                new CodeEvidenceExtractionContext("trace the flow", EvidenceExtractionStage.POST_OPERATION,
                        List.of(source), 5));

        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .containsExactly("Run", "first.Receive", "Transform", "second.Publish", "Complete");
    }

    @Test
    void navigationExtractorRetainsPrimaryDepthAndCompanionCoverageWithinGlobalBound() {
        String crowdedBody = "void Coordinate() {\n"
                + java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> "stage" + index + "();")
                .collect(java.util.stream.Collectors.joining("\n"))
                + "\n}";
        CodeSearchResult crowded = result("src/Coordinator.java", "Coordinate", crowdedBody, Map.of());
        CodeSearchResult companion = result("src/Companion.java", "Assist", """
                void Assist() {
                    prepare();
                    finalizeCycle();
                }
                """, Map.of());

        CodeEvidenceIr ir = new NavigationEvidenceExtractor().extract(
                new CodeEvidenceExtractionContext("trace the lifecycle",
                        EvidenceExtractionStage.POST_OPERATION,
                        List.of(crowded, companion), 6));

        assertThat(ir.navigationHandles()).hasSize(20);
        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .contains(
                        "Coordinate", "Assist",
                        "stage0", "stage15",
                        "prepare", "finalizeCycle");
    }

    @Test
    void navigationExtractorBoundsSourceBreadthAndReservesOperandsForRelevantCallables() {
        List<CodeSearchResult> sources = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            sources.add(result(
                    "src/Flow" + index + ".java",
                    "coordinate" + index,
                    "void coordinate" + index + "() { begin" + index + "(); end" + index + "(); }",
                    Map.of()));
        }

        CodeEvidenceIr ir = new NavigationEvidenceExtractor().extract(
                new CodeEvidenceExtractionContext("trace the lifecycle",
                        EvidenceExtractionStage.POST_OPERATION,
                        sources, 24));

        assertThat(ir.navigationHandles()).hasSize(24);
        assertThat(ir.navigationHandles().stream()
                .filter(handle -> handle.kind() == CodeNavigationHandle.Kind.DEFINITION))
                .hasSize(8);
        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .contains("coordinate0", "begin0", "end0", "coordinate7", "begin7", "end7")
                .doesNotContain("coordinate8", "begin8", "end8");
    }

    @Test
    void navigationExtractorPrefersCallableBodiesOverBroadContainersForTheSameFile() {
        CodeSearchResult broad = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Flow.java",
                "class", "Flow", "Flow", "", "sample", null, null, 0,
                1, 500, "class Flow { void unrelated() { broadOnlyCall(); } }", 1.0, Map.of());
        CodeSearchResult method = result(
                "src/Flow.java", "run", "void run() { focusedCall(); }", Map.of());

        CodeEvidenceIr ir = new NavigationEvidenceExtractor().extract(
                new CodeEvidenceExtractionContext("trace run", EvidenceExtractionStage.POST_OPERATION,
                        List.of(broad, method), 8));

        assertThat(ir.navigationHandles()).extracting(CodeNavigationHandle::symbol)
                .contains("run", "focusedCall")
                .doesNotContain("broadOnlyCall");
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
