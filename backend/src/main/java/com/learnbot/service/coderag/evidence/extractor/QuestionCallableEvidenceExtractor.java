package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.evidence.CodeLexicalEvidenceSelector;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Preserves bounded implementation bodies for callable identities explicitly authored by the user. */
@Component
public final class QuestionCallableEvidenceExtractor implements EvidenceExtractor {
    @Override
    public String id() {
        return "question-callable";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_OPERATION, EvidenceExtractionStage.PRE_ANSWER);
    }

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        return context != null && stages().contains(context.stage())
                && !context.question().isBlank()
                && context.evidence().stream().anyMatch(result -> isCallableBody(context.question(), result));
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        if (!supports(context)) return CodeEvidenceIr.empty();
        Map<String, CodeEvidenceItem> items = new LinkedHashMap<>();
        List<CodeEvidenceSignal> signals = new ArrayList<>();
        for (CodeSearchResult result : context.evidence()) {
            if (!isCallableBody(context.question(), result)) continue;
            String evidenceId = CodeEvidenceItem.evidenceId(result);
            if (items.containsKey(evidenceId)) continue;
            if (items.size() >= context.maxItemsPerExtractor()) break;
            CodeIntelligenceAuthority authority = EvidenceExtractionSupport.directSyntaxAuthority(result);
            CodeEvidenceItem item = new CodeEvidenceItem(
                    evidenceId, result, Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE), authority);
            items.put(evidenceId, item);
            signals.add(new CodeEvidenceSignal(
                    CodeEvidenceSignal.Type.QUESTION_CALLABLE_BODY,
                    evidenceId,
                    0.95,
                    "The user question explicitly names this callable and the indexed source contains its body."));
        }
        return new CodeEvidenceIr(
                List.copyOf(items.values()), List.of(), List.of(), List.copyOf(signals), List.of(), List.of());
    }

    private boolean isCallableBody(String question, CodeSearchResult result) {
        if (result == null || result.content() == null || result.content().isBlank()
                || (result.methodName() == null || result.methodName().isBlank())
                || !CodeLexicalEvidenceSelector.hasExactTrustedCallableMatch(question, result)) {
            return false;
        }
        Object body = EvidenceExtractionSupport.metadataValue(result, "callableBodyPresent");
        return body == null || Boolean.parseBoolean(String.valueOf(body));
    }
}
