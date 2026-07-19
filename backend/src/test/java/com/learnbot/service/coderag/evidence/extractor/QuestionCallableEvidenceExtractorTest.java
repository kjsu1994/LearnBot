package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.evidence.CodeEvidenceRetentionPlan;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionCallableEvidenceExtractorTest {
    @Test
    void preservesDistinctCallableBodiesExplicitlyNamedByTheQuestion() {
        CodeSearchResult active = result("loadActiveState", true);
        CodeSearchResult cached = result("loadCachedState", true);
        CodeSearchResult unrelated = result("refreshDiagnostics", true);
        CodeSearchResult declarationOnly = result("loadLegacyState", false);
        QuestionCallableEvidenceExtractor extractor = new QuestionCallableEvidenceExtractor();

        var ir = extractor.extract(new CodeEvidenceExtractionContext(
                "Compare loadActiveState and loadCachedState with loadLegacyState",
                EvidenceExtractionStage.PRE_ANSWER,
                List.of(active, cached, unrelated, declarationOnly)));
        CodeEvidenceRetentionPlan retention = CodeEvidenceRetentionPlan.from(ir);

        assertThat(ir.evidenceItems()).extracting(item -> item.source().methodName())
                .containsExactly("loadActiveState", "loadCachedState");
        assertThat(ir.signals()).extracting(CodeEvidenceSignal::type)
                .containsOnly(CodeEvidenceSignal.Type.QUESTION_CALLABLE_BODY);
        assertThat(retention.lookup(ir.evidenceItems().get(0).evidenceId())).get()
                .satisfies(entry -> assertThat(entry.groups())
                        .contains("question_callable:loadactivestate"));
    }

    private CodeSearchResult result(String method, boolean bodyPresent) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/StateReader.java",
                "method", method, "StateReader", method, "app", null, null, 1,
                10, 24, bodyPresent ? "State " + method + "() { return state; }" : "State " + method + "();",
                0.8, Map.of("callableBodyPresent", bodyPresent));
    }
}
