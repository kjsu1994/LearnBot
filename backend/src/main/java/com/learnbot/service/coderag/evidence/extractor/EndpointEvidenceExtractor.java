package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class EndpointEvidenceExtractor implements EvidenceExtractor {
    private static final String RELATION = "EXPOSES_ENDPOINT";

    @Override
    public String id() {
        return "endpoint";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_SEED, EvidenceExtractionStage.POST_OPERATION,
                EvidenceExtractionStage.PRE_ANSWER);
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        return context != null && context.evidence().stream().anyMatch(result ->
                !EvidenceExtractionSupport.metadata(result, "endpointRoute").isBlank()
                        || EvidenceExtractionSupport.hasRelation(result, RELATION));
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        List<CodeEvidenceItem> items = new ArrayList<>();
        List<CodeEvidenceFact> facts = new ArrayList<>();
        List<CodeEvidenceConstraint> constraints = new ArrayList<>();
        List<CodeEvidenceSignal> signals = new ArrayList<>();
        int limit = context.maxItemsPerExtractor();
        String requiredRoute = requiredExactRoute(context);
        boolean exactConstraintAdded = false;

        for (CodeSearchResult result : EvidenceExtractionSupport.bounded(context.evidence(), limit)) {
            String rawRoute = EvidenceExtractionSupport.metadata(result, "endpointRoute");
            boolean graphRelation = EvidenceExtractionSupport.hasRelation(result, RELATION);
            if (rawRoute.isBlank() && !graphRelation) continue;

            LinkedHashSet<CodeEvidenceItem.Kind> kinds = new LinkedHashSet<>();
            kinds.add(CodeEvidenceItem.Kind.ENDPOINT);
            kinds.add(CodeEvidenceItem.Kind.DIRECT_SOURCE);
            if (graphRelation) kinds.add(CodeEvidenceItem.Kind.GRAPH_RELATION);
            CodeIntelligenceAuthority authority = EvidenceExtractionSupport.directSyntaxAuthority(result);
            CodeEvidenceItem item = new CodeEvidenceItem(CodeEvidenceItem.evidenceId(result), result, kinds,
                    authority);
            items.add(item);

            String route = EvidenceExtractionSupport.normalizeRoute(rawRoute);
            if (!route.isBlank() && facts.size() < limit) {
                CodeEvidenceFact routeFact = CodeEvidenceFact.of(item.evidenceId(),
                        EvidenceExtractionSupport.subject(result), RELATION, route,
                        CodeEvidenceFact.Exactness.NORMALIZED, 1.0, authority);
                facts.add(routeFact);
                if (!exactConstraintAdded && route.equals(requiredRoute)) {
                    constraints.add(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                            routeFact.factId(), "Preserve the endpoint route selected by explicit or operation provenance."));
                    exactConstraintAdded = true;
                }

                String method = EvidenceExtractionSupport.metadata(result, "httpMethod").toUpperCase(Locale.ROOT);
                if (!method.isBlank() && facts.size() < limit) {
                    facts.add(CodeEvidenceFact.of(item.evidenceId(), EvidenceExtractionSupport.subject(result),
                            "HTTP_METHOD", method, CodeEvidenceFact.Exactness.NORMALIZED, 1.0, authority));
                }
            } else if (route.isBlank()) {
                constraints.add(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED,
                        item.evidenceId(), "The endpoint relation has no route literal in this evidence."));
            }
            signals.add(new CodeEvidenceSignal(CodeEvidenceSignal.Type.ENDPOINT_STRUCTURE,
                    item.evidenceId(), graphRelation && !route.isBlank() ? 1.0 : 0.75,
                    "Endpoint structure was extracted without changing the search score."));
        }
        return new CodeEvidenceIr(items, facts, constraints, signals, List.of(), List.of());
    }

    private String requiredExactRoute(CodeEvidenceExtractionContext context) {
        Set<String> evidenceRoutes = routes(context.evidence());
        Set<String> explicitRoutes = CodeEndpointQueryVariants.routes(context.question()).stream()
                .map(EvidenceExtractionSupport::normalizeRoute)
                .filter(route -> !route.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (explicitRoutes.size() == 1) {
            String explicitRoute = explicitRoutes.iterator().next();
            if (evidenceRoutes.contains(explicitRoute)) return explicitRoute;
        }

        Set<String> operationRoutes = context.evidence().stream()
                .filter(result -> CodeEvidenceOperationProvenance.from(result).stream()
                        .anyMatch(value -> "find_endpoint".equals(value.operationType())))
                .map(result -> EvidenceExtractionSupport.normalizeRoute(
                        EvidenceExtractionSupport.metadata(result, "endpointRoute")))
                .filter(route -> !route.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return operationRoutes.size() == 1 ? operationRoutes.iterator().next() : "";
    }

    private Set<String> routes(List<CodeSearchResult> evidence) {
        return evidence.stream()
                .map(result -> EvidenceExtractionSupport.normalizeRoute(
                        EvidenceExtractionSupport.metadata(result, "endpointRoute")))
                .filter(route -> !route.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
