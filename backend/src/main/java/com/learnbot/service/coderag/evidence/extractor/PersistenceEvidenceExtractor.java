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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PersistenceEvidenceExtractor implements EvidenceExtractor {
    private static final List<String> RELATIONS = List.of(
            "REPOSITORY_FOR", "QUERIES_ENTITY", "READS_FIELD", "WRITES_FIELD",
            "USES_ENTITY", "FILTERS_BY_PROPERTY");

    @Override
    public String id() {
        return "persistence";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_SEED, EvidenceExtractionStage.POST_OPERATION,
                EvidenceExtractionStage.PRE_ANSWER);
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        return context != null && context.evidence().stream().anyMatch(result ->
                RELATIONS.stream().anyMatch(relation -> EvidenceExtractionSupport.hasRelation(result, relation))
                        || !EvidenceExtractionSupport.metadata(result, "declaredQuery").isBlank()
                        || !EvidenceExtractionSupport.metadata(result, "persistenceOperation").isBlank());
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        int limit = context.maxItemsPerExtractor();
        Map<String, CodeEvidenceItem> items = new LinkedHashMap<>();
        List<CodeEvidenceFact> facts = new ArrayList<>();
        List<CodeEvidenceConstraint> constraints = new ArrayList<>();
        List<CodeEvidenceSignal> signals = new ArrayList<>();

        for (CodeSearchResult result : context.evidence()) {
            if (facts.size() >= limit) break;
            Set<String> observedRelations = EvidenceExtractionSupport.relations(result);
            String declaredQuery = EvidenceExtractionSupport.metadata(result, "declaredQuery");
            String operation = EvidenceExtractionSupport.metadata(result, "persistenceOperation");
            boolean applicable = RELATIONS.stream().anyMatch(observedRelations::contains)
                    || !declaredQuery.isBlank() || !operation.isBlank();
            if (!applicable) continue;

            String evidenceId = CodeEvidenceItem.evidenceId(result);
            items.putIfAbsent(evidenceId, EvidenceExtractionSupport.item(result,
                    CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.GRAPH_RELATION,
                    CodeEvidenceItem.Kind.PERSISTENCE));
            String subject = EvidenceExtractionSupport.subject(result);
            for (String relation : RELATIONS) {
                if (facts.size() >= limit || !observedRelations.contains(relation)) continue;
                String target = EvidenceExtractionSupport.firstMetadata(result,
                        "relationTarget", "targetSymbol", "entityName", "fieldName");
                if (target.isBlank() && relation.equals(EvidenceExtractionSupport.metadata(result, "graphEdgeType"))) {
                    target = EvidenceExtractionSupport.lastGraphNode(result);
                }
                facts.add(CodeEvidenceFact.of(evidenceId, subject, relation,
                        target.isBlank() ? "present" : EvidenceExtractionSupport.truncate(target, 160),
                        CodeEvidenceFact.Exactness.NORMALIZED, 0.9,
                        EvidenceExtractionSupport.authority(result)));
            }
            if (!declaredQuery.isBlank() && facts.size() < limit) {
                CodeEvidenceFact queryFact = CodeEvidenceFact.of(evidenceId, subject, "DECLARES_QUERY",
                        EvidenceExtractionSupport.truncate(declaredQuery, 320),
                        CodeEvidenceFact.Exactness.EXACT, 1.0,
                        EvidenceExtractionSupport.authority(result));
                facts.add(queryFact);
                constraints.add(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                        queryFact.factId(), "Preserve the directly observed declared query when it supports the claim."));
            }
            if (!operation.isBlank() && facts.size() < limit) {
                facts.add(CodeEvidenceFact.of(evidenceId, subject, "PERSISTENCE_OPERATION",
                        EvidenceExtractionSupport.truncate(operation, 80),
                        CodeEvidenceFact.Exactness.NORMALIZED, 1.0,
                        EvidenceExtractionSupport.authority(result)));
            }
            signals.add(new CodeEvidenceSignal(CodeEvidenceSignal.Type.PERSISTENCE_RELATION,
                    evidenceId, 0.9, "Typed persistence evidence was extracted without mutating retrieval scores."));
        }
        return new CodeEvidenceIr(List.copyOf(items.values()), facts, constraints, signals, List.of(), List.of());
    }
}
