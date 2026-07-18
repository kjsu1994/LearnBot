package com.learnbot.service.coderag.model;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceIrTest {

    @Test
    void retainEvidenceKeepsTheSelectedSourcesTypedClosureOnly() {
        CodeSearchResult keptSource = result("src/Kept.java", "coordinate");
        CodeSearchResult droppedSource = result("src/Dropped.java", "distract");
        CodeEvidenceItem keptItem = CodeEvidenceItem.from(
                keptSource, CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION);
        CodeEvidenceItem droppedItem = CodeEvidenceItem.from(
                droppedSource, CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION);
        CodeEvidenceFact keptFact = CodeEvidenceFact.of(
                keptItem.evidenceId(), "coordinate", "CALLS_SYMBOL", "commit",
                CodeEvidenceFact.Exactness.NORMALIZED, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact droppedFact = CodeEvidenceFact.of(
                droppedItem.evidenceId(), "distract", "CALLS_SYMBOL", "noise",
                CodeEvidenceFact.Exactness.NORMALIZED, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeNavigationHandle keptHandle = CodeNavigationHandle.of(
                CodeNavigationHandle.Kind.CALL, keptSource.filePath(), "store.commit",
                keptSource.chunkId(), 14, 14, keptItem.evidenceId());
        CodeNavigationHandle droppedHandle = CodeNavigationHandle.of(
                CodeNavigationHandle.Kind.CALL, droppedSource.filePath(), "logger.debug",
                droppedSource.chunkId(), 20, 20, droppedItem.evidenceId());
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(keptItem, droppedItem),
                List.of(keptFact, droppedFact),
                List.of(
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                                keptFact.factId(), "keep"),
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.NAVIGATION_ONLY,
                                droppedHandle.handleId(), "drop")),
                List.of(
                        new CodeEvidenceSignal(CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                                keptItem.evidenceId(), 1.0, "keep"),
                        new CodeEvidenceSignal(CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                                droppedItem.evidenceId(), 1.0, "drop")),
                List.of(keptHandle, droppedHandle),
                List.of(new CodeEvidenceIr.Diagnostic(
                        "navigation", CodeEvidenceIr.DiagnosticStatus.SUCCESS, "observed")));

        CodeEvidenceIr retained = ir.retainEvidence(Set.of(keptItem.evidenceId()));

        assertThat(retained.evidenceItems()).containsExactly(keptItem);
        assertThat(retained.facts()).containsExactly(keptFact);
        assertThat(retained.constraints()).extracting(CodeEvidenceConstraint::targetId)
                .containsExactly(keptFact.factId());
        assertThat(retained.signals()).extracting(CodeEvidenceSignal::sourceEvidenceId)
                .containsExactly(keptItem.evidenceId());
        assertThat(retained.navigationHandles()).containsExactly(keptHandle);
        assertThat(retained.diagnostics()).hasSize(1);
    }

    @Test
    void retainNavigationEvidenceDoesNotRestoreFactsOmittedByExcerpting() {
        CodeSearchResult source = result("src/Coordinator.java", "coordinate");
        CodeEvidenceItem item = CodeEvidenceItem.from(
                source, CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION);
        CodeEvidenceFact omittedFact = CodeEvidenceFact.of(
                item.evidenceId(), "internal.secret", "ASSIGNS_EXPRESSION", "hidden.compute()",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeNavigationHandle retainedHandle = CodeNavigationHandle.of(
                CodeNavigationHandle.Kind.CALL, source.filePath(), "store.commit",
                source.chunkId(), 14, 14, item.evidenceId());
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(item), List.of(omittedFact), List.of(
                new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                        omittedFact.factId(), "omit"),
                new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.NAVIGATION_ONLY,
                        retainedHandle.handleId(), "keep")),
                List.of(new CodeEvidenceSignal(CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                        item.evidenceId(), 1.0, "omit")),
                List.of(retainedHandle), List.of());

        CodeEvidenceIr retained = ir.retainNavigationEvidence(Set.of(item.evidenceId()));

        assertThat(retained.evidenceItems()).containsExactly(item);
        assertThat(retained.facts()).isEmpty();
        assertThat(retained.signals()).isEmpty();
        assertThat(retained.navigationHandles()).containsExactly(retainedHandle);
        assertThat(retained.constraints()).extracting(CodeEvidenceConstraint::targetId)
                .containsExactly(retainedHandle.handleId());
    }

    @Test
    void mergeKeepsLaterCumulativeSourceSnapshotWhenAuthorityIsEqual() {
        CodeSearchResult first = result("src/Pipeline.java", "coordinate");
        CodeSearchResult later = new CodeSearchResult(
                first.chunkId(), first.repositoryId(), first.fileId(), first.repositoryName(), first.filePath(),
                first.chunkType(), first.symbolName(), first.className(), first.methodName(), first.namespaceName(),
                first.controlName(), first.eventName(), first.chunkIndex(), first.lineStart(), first.lineEnd(),
                first.content(), first.score(), Map.of(
                "indexVersion", first.metadata().get("indexVersion"),
                "codeIntelligenceAuthority", "SYNTAX",
                "graphExpanded", true,
                "graphDirection", "FORWARD"));

        CodeEvidenceItem merged = CodeEvidenceItem.from(first, CodeEvidenceItem.Kind.DIRECT_SOURCE)
                .merge(CodeEvidenceItem.from(later,
                        CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION));

        assertThat(merged.source().metadata())
                .containsEntry("graphExpanded", true)
                .containsEntry("graphDirection", "FORWARD");
        assertThat(merged.kinds()).containsExactlyInAnyOrder(
                CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION);
    }

    private CodeSearchResult result(String path, String method) {
        UUID chunkId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        return new CodeSearchResult(
                chunkId, UUID.randomUUID(), UUID.randomUUID(), "repository", path,
                "method", method, "Sample", method, "sample", null, null,
                0, 10, 30, "void " + method + "() {}", 1.0,
                Map.of(
                        "indexVersion", indexVersion.toString(),
                        "codeIntelligenceAuthority", "SYNTAX"));
    }
}
