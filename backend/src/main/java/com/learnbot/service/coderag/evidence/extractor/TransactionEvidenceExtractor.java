package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class TransactionEvidenceExtractor implements EvidenceExtractor {
    private static final String RELATION = "TRANSACTION_BOUNDARY";

    @Override
    public String id() {
        return "transaction";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_SEED, EvidenceExtractionStage.POST_OPERATION,
                EvidenceExtractionStage.PRE_ANSWER);
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        return context != null && context.evidence().stream()
                .anyMatch(result -> EvidenceExtractionSupport.hasRelation(result, RELATION));
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        List<CodeEvidenceItem> items = new ArrayList<>();
        List<CodeEvidenceFact> facts = new ArrayList<>();
        List<CodeEvidenceConstraint> constraints = new ArrayList<>();
        List<CodeEvidenceSignal> signals = new ArrayList<>();
        int limit = context.maxItemsPerExtractor();

        for (CodeSearchResult result : context.evidence()) {
            if (items.size() >= limit) break;
            if (!EvidenceExtractionSupport.hasRelation(result, RELATION)) continue;
            CodeEvidenceItem item = EvidenceExtractionSupport.item(result, CodeEvidenceItem.Kind.DIRECT_SOURCE,
                    CodeEvidenceItem.Kind.GRAPH_RELATION, CodeEvidenceItem.Kind.TRANSACTION);
            items.add(item);
            String provenance = EvidenceExtractionSupport.firstMetadata(result,
                    "transactionAnnotation", "transactionBoundary", "annotation");
            boolean inherited = EvidenceExtractionSupport.metadataBoolean(result, "transactionInherited");
            CodeEvidenceFact fact = CodeEvidenceFact.of(item.evidenceId(),
                    EvidenceExtractionSupport.subject(result), RELATION,
                    provenance.isBlank() ? "present" : EvidenceExtractionSupport.truncate(provenance, 160),
                    inherited ? CodeEvidenceFact.Exactness.INFERRED : CodeEvidenceFact.Exactness.NORMALIZED,
                    inherited ? 0.85 : 1.0, item.authority());
            facts.add(fact);
            if (provenance.isBlank()) {
                constraints.add(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED,
                        fact.factId(), "The graph relation lacks direct transaction annotation provenance."));
            }
            signals.add(new CodeEvidenceSignal(CodeEvidenceSignal.Type.TRANSACTION_BOUNDARY,
                    item.evidenceId(), inherited ? 0.75 : 1.0,
                    "A typed transaction-boundary relation was observed."));
        }
        return new CodeEvidenceIr(items, facts, constraints, signals, List.of(), List.of());
    }
}
