package com.learnbot.service;

import java.util.LinkedHashSet;
import java.util.Set;

public record CodeIntelligenceShadowReport(
        boolean equivalent,
        int sourceNodes,
        int irNodes,
        int sourceEdges,
        int irEdges,
        Set<String> missingNodeKeys,
        Set<String> missingEdgeKeys
) {
    static CodeIntelligenceShadowReport compare(CodeGraph source, CodeGraph normalized) {
        Set<String> sourceNodeKeys = nodeKeys(source);
        Set<String> irNodeKeys = nodeKeys(normalized);
        Set<String> sourceEdgeKeys = edgeKeys(source);
        Set<String> irEdgeKeys = edgeKeys(normalized);
        Set<String> missingNodes = new LinkedHashSet<>(sourceNodeKeys);
        missingNodes.removeAll(irNodeKeys);
        Set<String> missingEdges = new LinkedHashSet<>(sourceEdgeKeys);
        missingEdges.removeAll(irEdgeKeys);
        boolean equivalent = missingNodes.isEmpty() && missingEdges.isEmpty()
                && sourceNodeKeys.size() == irNodeKeys.size()
                && sourceEdgeKeys.size() == irEdgeKeys.size();
        return new CodeIntelligenceShadowReport(
                equivalent,
                sourceNodeKeys.size(), irNodeKeys.size(), sourceEdgeKeys.size(), irEdgeKeys.size(),
                Set.copyOf(missingNodes), Set.copyOf(missingEdges));
    }

    private static Set<String> nodeKeys(CodeGraph graph) {
        if (graph == null || graph.nodes() == null) return Set.of();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        graph.nodes().stream().filter(java.util.Objects::nonNull)
                .map(CodeGraphNode::key).filter(java.util.Objects::nonNull).forEach(keys::add);
        return keys;
    }

    private static Set<String> edgeKeys(CodeGraph graph) {
        if (graph == null || graph.edges() == null) return Set.of();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        graph.edges().stream().filter(java.util.Objects::nonNull)
                .map(edge -> edge.sourceKey() + "|" + edge.type() + "|" + edge.targetKey())
                .forEach(keys::add);
        return keys;
    }
}
