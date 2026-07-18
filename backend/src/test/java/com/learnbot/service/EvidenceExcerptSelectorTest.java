package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExcerptSelectorTest {

    @Test
    void returnsFullChunkWhenItFitsTheAvailableBudget() {
        String content = """
                public void parse(String value) {
                    decimal parsed = decimal.Parse(value);
                    validate(parsed);
                }
                """;

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "How does parse validate value?", result(content), content.length() + 20);

        assertThat(excerpt.kind()).isEqualTo("FULL_CHUNK");
        assertThat(excerpt.contentComplete()).isTrue();
        assertThat(excerpt.text()).contains("decimal.Parse", "validate(parsed)");
    }

    @Test
    void selectsRelevantLateWindowsInsteadOfStoppingAtEarlyMatches() {
        String prefix = IntStream.range(0, 30)
                .mapToObj(index -> "var commandParser" + index + " = arguments[" + index + "];")
                .reduce("", (left, right) -> left + right + "\n");
        String content = prefix + """
                /*
                if (inputRange.IsArgRange(float.Parse(argument)) == false) {
                    LogWarning("Argument value is out of range");
                }
                */
                string argText = arguments[i]?.ToString();
                if (decimal.TryParse(argText, out decimal argValue) == false) {
                    LogWarning("Argument value is not numeric");
                    return;
                }
                if (inputRange.IsArgRange(argValue) == false) {
                    string message = "Argument value is out of range";
                    LogWarning(message);
                    return;
                }
                """;

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Argument value is out of range IsArgRange validation", result(content), 900);

        assertThat(excerpt.kind()).isEqualTo("SCORED_WINDOWS");
        assertThat(excerpt.text())
                .contains("decimal.TryParse", "IsArgRange(argValue)", "LogWarning(message)", "return");
    }

    @Test
    void directReadPreservesDeclarationAndRelevantBodyInsteadOfUnrelatedTail() {
        String content = "public void processRequest() {\n"
                + "    auditStep();\n".repeat(100)
                + "    stateStore.commitVersion(versionId);\n"
                + "    auditStep();\n".repeat(100)
                + "}";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "processRequest"),
                Map.of(
                        "llmDirectRead", true,
                        "llmChecklistGoal", "commitVersion processed version"
                ));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Where is the version committed?", directRead, 360);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_ANCHORED");
        assertThat(excerpt.contentComplete()).isFalse();
        assertThat(excerpt.omittedByBudget()).isTrue();
        assertThat(excerpt.text())
                .contains(
                        "public void processRequest() {",
                        "stateStore.commitVersion(versionId)",
                        "omitted by prompt budget");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(360);
    }

    @Test
    void scoredMethodExcerptAlwaysKeepsExactDeclarationWithRelevantInterior() {
        String content = "public ChatResult chatResult(List<Model> candidates) {\n"
                + "    prepareRequest();\n".repeat(70)
                + "    tryNextCandidate(candidates);\n"
                + "    recordFailure();\n".repeat(70)
                + "}";
        CodeSearchResult method = withMethodIdentity(result(content), "chatResult");

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Does failure tryNextCandidate?", method, 320);

        assertThat(excerpt.kind()).isEqualTo("SCORED_WINDOWS");
        assertThat(excerpt.text())
                .contains(
                        "chatResult(List<Model> candidates) {",
                        "tryNextCandidate(candidates)");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(320);
    }

    @Test
    void structuralAnchorSkipsInvocationAndFindsALongMultilineDeclaration() {
        String parameters = IntStream.rangeClosed(1, 16)
                .mapToObj(index -> "        String parameter" + index + ",")
                .reduce("", (left, right) -> left + right + "\n");
        String content = "public void wrapper() {\n"
                + "    target.execute();\n"
                + "}\n"
                + "public Result execute(\n"
                + parameters
                + "        String finalParameter\n"
                + ") {\n"
                + "    return proofStore.load(finalParameter);\n"
                + "}\n";
        CodeSearchResult method = withMethodIdentity(result(content), "execute");

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Explain execute proofStore load", method, 520);

        assertThat(excerpt.text())
                .contains("public Result execute(", ") {", "proofStore.load(finalParameter)");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(520);
    }

    @Test
    void boundedDirectReadMarksBothUnrenderedEdgesWhenWindowsAreInterior() {
        String content = IntStream.rangeClosed(1, 80)
                .mapToObj(line -> line == 40
                        ? "    stateStore.persistTransition(value);"
                        : "    paddingStep(" + line + ");")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "missingDeclaration"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "persistTransition"));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Find persistTransition", directRead, 260);

        assertThat(excerpt.text())
                .startsWith("... lines 1-")
                .contains("stateStore.persistTransition(value)")
                .endsWith("omitted by prompt budget ...");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(260);
    }

    @Test
    void requestedMiddleRangeIsPreservedWithTruthfulExcerptBounds() {
        String content = IntStream.rangeClosed(100, 199)
                .mapToObj(line -> "source-line-" + line)
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        CodeSearchResult directRead = withLineRangeAndMetadata(
                result(content),
                100,
                199,
                Map.of(
                        "llmDirectRead", true,
                        "llmReadOperation", "read_file_range",
                        "llmRequestedLineStart", 145,
                        "llmRequestedLineEnd", 147
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "explain the requested range", directRead, 260);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_REQUESTED_RANGE");
        assertThat(excerpt.contentComplete()).isFalse();
        assertThat(excerpt.omittedByBudget()).isFalse();
        assertThat(excerpt.lineStart()).isEqualTo(145);
        assertThat(excerpt.lineEnd()).isEqualTo(147);
        assertThat(excerpt.text())
                .contains(
                        "outside requested range omitted: lines 100-144",
                        "source-line-145",
                        "source-line-146",
                        "source-line-147",
                        "outside requested range omitted: lines 148-199"
                )
                .doesNotContain("source-line-144", "source-line-148");
    }

    @Test
    void oversizedRequestedRangeIncludesRelevantCallFromSmallBoundaryPadding() {
        String content = IntStream.rangeClosed(100, 165)
                .mapToObj(line -> {
                    if (line == 100) {
                        return "public void finishIndex() {";
                    }
                    if (line == 119) {
                        return "    stateStore.commitVersion(versionId);";
                    }
                    if (line == 161) {
                        return "}";
                    }
                    return "    auditStep(" + line + ");";
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        CodeSearchResult directRead = withLineRangeAndMetadata(
                result(content),
                100,
                165,
                Map.of(
                        "llmDirectRead", true,
                        "llmReadOperation", "read_file_range",
                        "llmRequestedLineStart", 120,
                        "llmRequestedLineEnd", 160,
                        "llmChecklistGoal", "commit processed version"
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Where is the processed version committed?", directRead, 360);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_REQUESTED_RANGE_BOUNDED");
        assertThat(excerpt.contentComplete()).isFalse();
        assertThat(excerpt.omittedByBudget()).isTrue();
        assertThat(excerpt.lineStart()).isBetween(118, 119);
        assertThat(excerpt.text())
                .contains("stateStore.commitVersion(versionId)")
                .doesNotContain("auditStep(117)", "auditStep(163)");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(360);
    }

    @Test
    void oversizedRequestedRangeSelectsMultipleRelevantWindowsWithinBudget() {
        String content = IntStream.rangeClosed(200, 300)
                .mapToObj(line -> {
                    if (line == 200) {
                        return "public CodeAskResponse askPrioritized() {";
                    }
                    if (line == 231) {
                        return "    firstStage.apply(stageInput);";
                    }
                    if (line == 270) {
                        return "    secondStage.emit(stageOutput);";
                    }
                    if (line == 300) {
                        return "}";
                    }
                    return "    auditStep(" + line + ");";
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        CodeSearchResult directRead = withLineRangeAndMetadata(
                result(content),
                200,
                300,
                Map.of(
                        "llmDirectRead", true,
                        "llmReadOperation", "read_file_range",
                        "llmRequestedLineStart", 200,
                        "llmRequestedLineEnd", 300,
                        "llmChecklistGoal", "firstStage.apply secondStage.emit"
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Trace firstStage.apply through secondStage.emit", directRead, 420);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_REQUESTED_RANGE_BOUNDED");
        assertThat(excerpt.text())
                .contains(
                        "firstStage.apply(stageInput)",
                        "secondStage.emit(stageOutput)",
                        "auditStep(230)",
                        "auditStep(232)",
                        "auditStep(269)",
                        "auditStep(271)",
                        "omitted by prompt budget"
        );
        assertThat(excerpt.lineStart()).isEqualTo(228);
        assertThat(excerpt.lineEnd()).isBetween(272, 273);
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(420);
    }

    @Test
    void requestedRangeBudgetIncludesOutsideRangeOmissionMarkers() {
        String content = IntStream.rangeClosed(500, 530)
                .mapToObj(line -> line == 515
                        ? "    target.assign(value);"
                        : "    paddingStep(" + line + ");")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        CodeSearchResult directRead = withLineRangeAndMetadata(
                result(content),
                500,
                530,
                Map.of(
                        "llmDirectRead", true,
                        "llmReadOperation", "read_file_range",
                        "llmRequestedLineStart", 515,
                        "llmRequestedLineEnd", 515,
                        "llmRequestedSymbol", "target.assign"
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Explain target.assign", directRead, 120);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_REQUESTED_RANGE_BOUNDED");
        assertThat(excerpt.text())
                .contains(
                        "paddingStep(514)",
                        "target.assign(value)",
                        "paddingStep(516)"
                )
                .doesNotContain("outside requested range omitted");
        assertThat(excerpt.lineStart()).isEqualTo(513);
        assertThat(excerpt.lineEnd()).isEqualTo(517);
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(120);
    }

    @Test
    void oversizedRequestedRangeNeverExceedsStrictCallerBudget() {
        String content = IntStream.rangeClosed(400, 470)
                .mapToObj(line -> {
                    if (line == 400) {
                        return "public void finishIndex() {";
                    }
                    if (line == 430) {
                        return "    stateStore.commitVersion(versionId);";
                    }
                    if (line == 470) {
                        return "}";
                    }
                    return "    auditStep(" + line + ");";
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        CodeSearchResult directRead = withLineRangeAndMetadata(
                result(content),
                400,
                470,
                Map.of(
                        "llmDirectRead", true,
                        "llmRequestedLineStart", 400,
                        "llmRequestedLineEnd", 470,
                        "llmChecklistGoal", "commit processed version"
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Find commitVersion", directRead, 96);

        assertThat(excerpt.kind()).isEqualTo("DIRECT_READ_REQUESTED_RANGE_BOUNDED");
        assertThat(excerpt.text()).contains("commitVersion");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(96);
    }

    @Test
    void canonicalChunkEnvelopeDoesNotConsumeSourceLinesOrDropMethodTail() {
        String content = canonicalEnvelope("src/Widget.cs", 114, 124, line -> switch (line) {
            case 114 -> "private void UpdateState() {";
            case 123 -> "    target.Visible = true;";
            case 124 -> "}";
            default -> "    auditStep(" + line + ");";
        });
        CodeSearchResult directRead = withLineRangeAndMetadata(
                withMethodIdentity(result(content), "UpdateState"),
                114,
                124,
                Map.of("llmDirectRead", true)
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Explain UpdateState", directRead, 2_000);

        assertThat(excerpt.kind()).isEqualTo("FULL_CHUNK");
        assertThat(excerpt.text())
                .contains("114: private void UpdateState() {", "123:     target.Visible = true;", "124: }")
                .doesNotContain("File: src/Widget.cs", "Lines: 114-124", "\nsrc/Widget.cs");
        assertThat(excerpt.lineStart()).isEqualTo(114);
        assertThat(excerpt.lineEnd()).isEqualTo(124);
    }

    @Test
    void canonicalChunkEnvelopeKeepsDeepRelevantLinesUnderDirectReadBudget() {
        String content = canonicalEnvelope("src/Workflow.java", 200, 260, line -> switch (line) {
            case 200 -> "void completeWorkflow() {";
            case 247 -> "    stateStore.commitVersion(versionId);";
            case 260 -> "}";
            default -> "    auditStep(" + line + ");";
        });
        CodeSearchResult directRead = withLineRangeAndMetadata(
                withMethodIdentity(result(content), "completeWorkflow"),
                200,
                260,
                Map.of(
                        "llmDirectRead", true,
                        "llmChecklistGoal", "commitVersion processed version"
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Where is the processed version committed?", directRead, 360);

        assertThat(excerpt.text()).contains("stateStore.commitVersion(versionId)");
        assertThat(excerpt.text()).doesNotContain("File: src/Workflow.java", "Lines: 200-260");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(360);
    }

    @Test
    void requestedLineRangeMapsToCanonicalEnvelopeSourceOffsets() {
        String content = canonicalEnvelope("src/Assignment.java", 100, 110, line -> line == 105
                ? "    target.assign(value);"
                : "    paddingStep(" + line + ");");
        CodeSearchResult directRead = withLineRangeAndMetadata(
                result(content),
                100,
                110,
                Map.of(
                        "llmDirectRead", true,
                        "llmRequestedLineStart", 105,
                        "llmRequestedLineEnd", 105,
                        "llmRequestedSymbol", "target.assign"
                )
        );

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Explain target.assign", directRead, 180);

        assertThat(excerpt.text())
                .contains("103:     paddingStep(103);", "105:     target.assign(value);", "107:     paddingStep(107);")
                .doesNotContain("102:     paddingStep(102);", "108:     paddingStep(108);");
        assertThat(excerpt.lineStart()).isEqualTo(103);
        assertThat(excerpt.lineEnd()).isEqualTo(107);
    }

    @Test
    void scoredWindowsReserveMarkersAndReportOnlyCompletelyRenderedLines() {
        String content = IntStream.range(0, 20)
                .mapToObj(line -> switch (line) {
                    case 3 -> "alpha00";
                    case 15 -> "beta000";
                    default -> "pad%04d".formatted(line);
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "alpha beta", result(content), 120);

        Set<String> sourceLines = Set.copyOf(content.lines().toList());
        var renderedSourceLines = excerpt.text().lines()
                .filter(line -> !line.startsWith("..."))
                .toList();
        assertThat(excerpt.kind()).isEqualTo("SCORED_WINDOWS");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(120);
        assertThat(renderedSourceLines).isNotEmpty().allMatch(sourceLines::contains);
        String lastRendered = renderedSourceLines.get(renderedSourceLines.size() - 1);
        int lastSourceIndex = content.lines().toList().indexOf(lastRendered);
        assertThat(excerpt.lineEnd()).isEqualTo(1 + lastSourceIndex);
    }

    @Test
    void structuralAnchorSkipsUnqualifiedLambdaInvocationBeforeDeclaration() {
        String content = "return Execute(() => FakeProof());\n"
                + "auditStep();\n".repeat(35)
                + "public Result Execute(Input input) {\n"
                + "    RealProof(input);\n"
                + "    return input.result();\n"
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "RealProof result"));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Explain Execute RealProof result", directRead, 180);

        assertThat(excerpt.text())
                .contains("public Result Execute(Input input) {", "RealProof(input)")
                .doesNotContain("FakeProof");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(180);
    }

    @Test
    void structuralAnchorSkipsInvocationInsideAConditionalBeforeDeclaration() {
        String content = "if (Execute(input)) { FakeProof(input); }\n"
                + "auditStep();\n".repeat(30)
                + "public Result Execute(Input input, String alpha, String beta, String gamma, "
                + "String delta, String epsilon) { return input.result(); RealProof(input); }\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "Execute"));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Execute", directRead, 160);

        assertThat(excerpt.text())
                .contains("public Result Execute", "RealProof(input)")
                .doesNotContain("FakeProof");
    }

    @Test
    void ambiguousStructuralCandidatesKeepBothBoundariesInsteadOfOnlyTheEarlyCall() {
        String content = "if Execute(input):\n"
                + "    FakeProof(input)\n"
                + "audit_step()\n".repeat(30)
                + "def Execute(input, alpha, beta, gamma, delta, epsilon, zeta, eta, theta):\n"
                + "    RealProof(input)\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "Execute"));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Execute", directRead, 160);

        assertThat(excerpt.text()).contains("def Execute", "RealProof(input)");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(160);
    }

    @Test
    void sameNameDeclarationsPreferTheRelevantMiddleSignatureAndBody() {
        String content = "public Result Execute(int value) { FirstProof(value); }\n"
                + "audit_step();\n".repeat(18)
                + "public Result Execute(String value) { TargetProof(value); }\n"
                + "audit_step();\n".repeat(18)
                + "public Result Execute(boolean value) { LastProof(value); }\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute(String)"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "String TargetProof"));

        for (int budget : java.util.List.of(120, 160, 200, 260)) {
            EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                    "Explain Execute(String) TargetProof", directRead, budget);

            assertThat(excerpt.text())
                    .as("budget %s", budget)
                    .contains("Execute(String", "TargetProof")
                    .doesNotContain("FirstProof", "LastProof");
            assertThat(excerpt.text().length()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void relevanceWindowCanExpandAPartiallyFittedStructuralAnchor() {
        String content = "public Result Execute(int input) {\n"
                + "    FirstProof(input);\n}\n"
                + "audit_step();\n".repeat(18)
                + "public Result Execute(String input) {\n"
                + "    TargetProof(input);\n}\n"
                + "audit_step();\n".repeat(18)
                + "public Result Execute(boolean input) {\n"
                + "    LastProof(input);\n}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute(String)"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "String TargetProof"));

        for (int budget : java.util.List.of(180, 190, 195, 200, 205, 210, 220, 230)) {
            EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                    "Explain Execute(String) TargetProof", directRead, budget);

            assertThat(excerpt.text()).as("budget %s", budget).contains("TargetProof(input)");
            assertThat(excerpt.text().length()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void deepRelevantBodyWinsBeforeTiedSameNameBoundariesFallback() {
        String first = "public Result Execute(int input) {\n    prepareInput(input);\n"
                + "    audit_step();\n".repeat(5) + "}\n";
        String middle = "public Result Execute(String input) {\n    prepareInput(input);\n"
                + "    audit_step();\n".repeat(5) + "    TargetProof(input);\n}\n";
        String last = "public Result Execute(boolean input) {\n    prepareInput(input);\n"
                + "    audit_step();\n".repeat(5) + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(first + middle + last), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "TargetProof"));

        for (int budget : java.util.List.of(160, 200, 260, 320)) {
            EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                    "Explain Execute TargetProof", directRead, budget);

            assertThat(excerpt.text()).as("budget %s", budget)
                    .contains("Execute(String input)", "TargetProof(input)")
                    .doesNotContain("Execute(int input)", "FirstProof");
            assertThat(excerpt.text().length()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void relevantSiblingBodyIsNotAttributedToTheNearestSameNameDeclaration() {
        String content = "public Result Execute(int input) {\n"
                + "    FirstProof(input);\n}\n"
                + "    padding();\n".repeat(8)
                + "public Result OtherMethod(String input) {\n"
                + "    prepareOther(input);\n"
                + "    TargetProof(input);\n}\n"
                + "    padding();\n".repeat(8)
                + "public Result Execute(boolean input) {\n"
                + "    LastProof(input);\n}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "TargetProof"));

        for (int budget : java.util.List.of(160, 200, 260, 320)) {
            EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                    "Explain Execute TargetProof", directRead, budget);

            assertThat(excerpt.text()).as("budget %s", budget)
                    .contains("OtherMethod", "TargetProof(input)")
                    .doesNotContain("Execute(int input)", "FirstProof(input)");
            assertThat(excerpt.text().length()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void multilineSiblingHeaderIsPreservedWithItsRelevantBody() {
        String content = "public Result Execute(int input) {\n"
                + "    FirstProof(input);\n}\n"
                + "    padding();\n".repeat(8)
                + "public Result OtherMethod(\n"
                + "        String input,\n"
                + "        String option,\n"
                + "        String mode\n"
                + ") {\n"
                + "    prepareOther(input);\n"
                + "    TargetProof(input);\n}\n"
                + "    padding();\n".repeat(8)
                + "public Result Execute(boolean input) {\n"
                + "    LastProof(input);\n}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "TargetProof"));

        for (int budget : java.util.List.of(160, 200, 260, 320)) {
            EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                    "Explain Execute TargetProof", directRead, budget);

            assertThat(excerpt.text()).as("budget %s", budget)
                    .contains("OtherMethod", "TargetProof(input)")
                    .doesNotContain("Execute(int input)", "FirstProof(input)");
            assertThat(excerpt.text().length()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void indentationSiblingBodyIsNotAttributedToTheNearestSameNameDeclaration() {
        String content = "def Execute(input):\n"
                + "    FirstProof(input)\n"
                + "padding()\n".repeat(16)
                + "def OtherMethod(input):\n"
                + "    prepare_other(input)\n"
                + "    TargetProof(input)\n"
                + "padding()\n".repeat(16)
                + "def Execute(flag):\n"
                + "    LastProof(flag)\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "TargetProof"));

        for (int budget : java.util.List.of(160, 200, 260, 320)) {
            EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                    "Explain Execute TargetProof", directRead, budget);

            assertThat(excerpt.text()).as("budget %s", budget)
                    .contains("OtherMethod", "TargetProof(input)")
                    .doesNotContain("def Execute(input)", "FirstProof(input)");
            assertThat(excerpt.text().length()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void largerBudgetsDoNotLetStructuralAnchorsHideRelevantImplementationLines() {
        String content = "public Result Execute(\n"
                + "        Input input,\n"
                + "        String alpha,\n"
                + "        String beta,\n"
                + "        String gamma,\n"
                + "        String delta,\n"
                + "        String epsilon,\n"
                + "        String zeta) {\n"
                + "    prepareInput(input);\n"
                + "    audit_step();\n".repeat(25)
                + "    TargetProof(input);\n"
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmChecklistGoal", "TargetProof"));

        for (int budget : java.util.List.of(160, 220, 280, 300, 320, 340, 360, 400)) {
            EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                    "Explain Execute TargetProof", directRead, budget);

            assertThat(excerpt.text()).as("budget %s", budget).contains("TargetProof(input)");
            assertThat(excerpt.text().length()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void longDirectReadRetainsAnExecutionSkeletonAcrossTheWholeCallable() {
        String content = "public Result Execute(Input input) {\n"
                + "    source.Begin(input);\n"
                + "    int firstPadding = 1;\n".repeat(20)
                + "    transformer.Advance(input);\n"
                + "    int secondPadding = 2;\n".repeat(20)
                + "    sink.Complete(input);\n"
                + "    return input.result();\n"
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmReadFulfilled", true));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "실행 흐름을 설명해줘", directRead, 520);

        assertThat(excerpt.text())
                .contains("Execute(Input input)", "source.Begin(input)",
                        "transformer.Advance(input)", "sink.Complete(input)");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(520);
    }

    @Test
    void tiedStructuralAnchorsDoNotBypassWholeChunkCallCoverage() {
        String content = "public Result Execute(int input) {\n"
                + "    source.Begin(input);\n"
                + "    int firstPadding = 1;\n".repeat(18)
                + "}\n"
                + "public Result Execute(String input) {\n"
                + "    transformer.Advance(input);\n"
                + "    int middlePadding = 2;\n".repeat(18)
                + "}\n"
                + "public Result Execute(boolean input) {\n"
                + "    sink.Complete(input);\n"
                + "    int lastPadding = 3;\n".repeat(18)
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmReadFulfilled", true));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "?ㅽ뻾 ?꾩껜 ?먮쫫", directRead, 620);

        assertThat(excerpt.text())
                .contains("source.Begin(input)", "transformer.Advance(input)", "sink.Complete(input)");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(620);
    }

    @Test
    void observedCallsAreReservedBeforeLexicalTailWindows() {
        String content = "private List<Item> expandRelated(List<Item> ranked) {\n"
                + "    Map<String, Item> graphState = new LinkedHashMap<>();\n"
                + "    String graphStatus = \"ready\";\n".repeat(18)
                + "    for (Item related : repository.relatedChunks(\n"
                + "            ranked,\n"
                + "            graphState.keySet())) {\n"
                + "        merge(graphState, related);\n"
                + "    }\n"
                + "    String graphSummary = graphStatus;\n".repeat(18)
                + "    return graphState.values().stream().toList();\n"
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "expandRelated"),
                Map.of("llmDirectRead", true, "llmReadFulfilled", true));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Explain the expandRelated graph state flow", directRead, 300);

        assertThat(excerpt.text())
                .contains("expandRelated", "repository.relatedChunks",
                        "graphState.values().stream().toList()");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(300);
    }

    @Test
    void narrowRequestedRangeStillKeepsLateCallsFromTheReturnedCallableChunk() {
        String content = "public Result Coordinate(Input input) {\n"
                + "    authService.Resolve(input);\n"
                + "    String verboseConfiguration = input.configurationWithA deliberatelyLongName();\n".repeat(7)
                + "    stateService.Validate(input);\n"
                + "    String middlePadding = input.anotherDeliberatelyLongConfigurationValue();\n".repeat(12)
                + "    workflowService.Dispatch(input);\n"
                + "    persistenceService.Complete(input);\n"
                + "    return input.Result();\n"
                + "}\n";
        CodeSearchResult directRead = withLineRangeAndMetadata(
                withMethodIdentity(result(content), "Coordinate"),
                100,
                100 + (int) content.lines().count() - 1,
                Map.of(
                        "llmDirectRead", true,
                        "llmRequestedLineStart", 100,
                        "llmRequestedLineEnd", 108));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Explain the service call flow", directRead, 420);

        assertThat(excerpt.text())
                .contains("Coordinate", "workflowService.Dispatch", "persistenceService.Complete");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(420);
    }

    @Test
    void behaviorRelevantCollaboratorCallsBeatGenericBoundaryCalls() {
        String content = "CodeAskResponse ask(CodeAskRequest request) {\n"
                + "    var user = currentUserProvider.currentUser();\n"
                + "    var space = authService.resolveSpace(user, request.spaceId());\n"
                + "    var spaces = authService.accessibleSpaceIds(user);\n"
                + "    if (space == null) space = spaces.get(0);\n"
                + "    repositorySpace(user, request.repositoryId());\n"
                + "    authService.requireSpace(user, space);\n"
                + "    if (!Boolean.TRUE.equals(request.conversational())) {\n"
                + "        return ragService.ask(request.repositoryId(), space, spaces, request.question());\n"
                + "    }\n"
                + "    var context = conversationService.prepare(user, space, request.question());\n"
                + "    var response = ragService.askConversational(\n"
                + "            request.repositoryId(), space, spaces, request.question(), context);\n"
                + "    return conversationService.saveCodeTurn(context, response);\n"
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "ask"),
                Map.of("llmDirectRead", true, "llmReadFulfilled", true));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "Which service calls does the ask controller method perform?", directRead, 680);

        assertThat(excerpt.text()).contains("ragService.ask(", "ragService.askConversational(");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(680);
    }

    @Test
    void callableFamilyKeepsLateCompletionCallWithoutQuestionLexicalOverlap() {
        String content = "void runIndex(IndexJob job) {\n"
                + "    repository.markRunning(job);\n"
                + "    scanner.scan(job);\n"
                + "    audit.record(job);\n".repeat(24)
                + "    repository.completeSuccessfulIndex(job.repositoryId(), job.indexVersion());\n"
                + "    metrics.finish(job);\n"
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "runIndex"),
                Map.of("llmDirectRead", true, "llmReadFulfilled", true));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "성공한 작업이 활성 상태로 전환되는 흐름", directRead, 420);

        assertThat(excerpt.text()).contains("completeSuccessfulIndex");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(420);
    }

    @Test
    void executionSkeletonKeepsAMultilineCollaboratorCallWithoutMatchingQuestionWords() {
        String content = "public List<Item> Execute(List<Item> input) {\n"
                + "    prepare.Open();\n"
                + "    int padding = 0;\n".repeat(18)
                + "    for (Item item : gateway.Load(\n"
                + "            input,\n"
                + "            options.resolve(),\n"
                + "            limits.current())) {\n"
                + "        output.add(item);\n"
                + "    }\n"
                + "    lifecycle.Close();\n"
                + "    return output;\n"
                + "}\n";
        CodeSearchResult directRead = withMetadata(
                withMethodIdentity(result(content), "Execute"),
                Map.of("llmDirectRead", true, "llmReadFulfilled", true));

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "처음부터 끝까지 어떤 흐름이야", directRead, 520);

        assertThat(excerpt.text()).contains("gateway.Load(", "lifecycle.Close()");
        assertThat(excerpt.text().length()).isLessThanOrEqualTo(520);
    }

    @Test
    void nonDirectScoringKeepsOriginalLineOffsetsAcrossBlankLines() {
        String padding = IntStream.range(0, 20)
                .mapToObj(index -> "padding-%02d".formatted(index))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        String tail = IntStream.range(0, 10)
                .mapToObj(index -> "tail-%02d".formatted(index))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        String content = "\n".repeat(5) + padding + "\ntargetProof();\n" + tail;
        int lineEnd = 100 + (int) content.lines().count() - 1;
        CodeSearchResult source = withLineRangeAndMetadata(result(content), 100, lineEnd, Map.of());

        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(
                "targetProof", source, 120);

        assertThat(excerpt.text()).contains("targetProof()");
        assertThat(excerpt.lineStart()).isEqualTo(122);
        assertThat(excerpt.lineEnd()).isEqualTo(128);
    }

    @Test
    void fullChunkFastPathsHonorCallerBudgetsBelowTheOldMinimum() {
        String content = "x".repeat(100);

        EvidenceExcerptSelector.Excerpt scored = EvidenceExcerptSelector.select(
                "", result(content), 96);
        EvidenceExcerptSelector.Excerpt direct = EvidenceExcerptSelector.select(
                "", withMetadata(result(content), Map.of("llmDirectRead", true)), 96);

        assertThat(scored.text().length()).isLessThanOrEqualTo(96);
        assertThat(direct.text().length()).isLessThanOrEqualTo(96);
        assertThat(scored.contentComplete()).isFalse();
        assertThat(direct.contentComplete()).isFalse();
    }

    private CodeSearchResult result(String content) {
        UUID repositoryId = UUID.randomUUID();
        return new CodeSearchResult(
                UUID.randomUUID(),
                repositoryId,
                UUID.randomUUID(),
                "test",
                "src/CommandParser.cs",
                "method",
                "LocalCommandParser",
                "CommandParser",
                "LocalCommandParser",
                "Example",
                null,
                null,
                0,
                1,
                Math.max(1, (int) content.lines().count()),
                content,
                0.9,
                Map.of(
                        "llmSupportedClaims", java.util.List.of("validates argument range and logs failures"),
                        "llmFollowUpQuery", "IsArgRange out of range validation"
                )
        );
    }

    private CodeSearchResult withMetadata(CodeSearchResult result, Map<String, Object> metadata) {
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score(), metadata);
    }

    private CodeSearchResult withMethodIdentity(CodeSearchResult result, String methodName) {
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), methodName, result.className(), methodName, result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score(), result.metadata());
    }

    private CodeSearchResult withLineRangeAndMetadata(
            CodeSearchResult result,
            int lineStart,
            int lineEnd,
            Map<String, Object> metadata
    ) {
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), lineStart, lineEnd,
                result.content(), result.score(), metadata);
    }

    private String canonicalEnvelope(
            String path,
            int lineStart,
            int lineEnd,
            java.util.function.IntFunction<String> sourceLine
    ) {
        String numberedSource = IntStream.rangeClosed(lineStart, lineEnd)
                .mapToObj(line -> line + ": " + sourceLine.apply(line))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        return "File: " + path + "\nLines: " + lineStart + "-" + lineEnd + "\n"
                + numberedSource + "\n" + path;
    }
}
