package com.learnbot.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CodeIntelligenceIr(
        int schemaVersion,
        String analyzerId,
        String languageId,
        CodeIntelligenceAuthority authority,
        CodeGraph graph,
        List<CodeAnalysisDiagnostic> diagnostics,
        Map<String, Object> extensions,
        CodeIntelligenceShadowReport shadowReport
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CodeIntelligenceIr {
        analyzerId = analyzerId == null ? "unknown" : analyzerId;
        languageId = languageId == null ? "unknown" : languageId;
        authority = authority == null ? CodeIntelligenceAuthority.UNKNOWN : authority;
        graph = graph == null ? new CodeGraph(List.of(), List.of()) : graph;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
        shadowReport = shadowReport == null ? CodeIntelligenceShadowReport.compare(graph, graph) : shadowReport;
    }

    static CodeIntelligenceIr fromAnalyzer(
            String analyzerId,
            String languageId,
            CodeIntelligenceAuthority authority,
            CodeGraph sourceGraph,
            List<CodeAnalysisDiagnostic> diagnostics,
            Map<String, Object> extensions
    ) {
        CodeGraph safeSource = sourceGraph == null ? new CodeGraph(List.of(), List.of()) : sourceGraph;
        CodeGraph normalized = withProvenance(safeSource, analyzerId, languageId, authority);
        CodeIntelligenceShadowReport shadow = CodeIntelligenceShadowReport.compare(safeSource, normalized);
        List<CodeAnalysisDiagnostic> enrichedDiagnostics = (diagnostics == null ? List.<CodeAnalysisDiagnostic>of() : diagnostics)
                .stream().map(diagnostic -> withShadowMetadata(diagnostic, shadow, analyzerId, languageId, authority)).toList();
        return new CodeIntelligenceIr(
                CURRENT_SCHEMA_VERSION, analyzerId, languageId, authority, normalized,
                enrichedDiagnostics, extensions, shadow);
    }

    private static CodeGraph withProvenance(
            CodeGraph graph,
            String analyzerId,
            String languageId,
            CodeIntelligenceAuthority authority
    ) {
        List<CodeGraphNode> nodes = graph.nodes() == null ? List.of() : graph.nodes().stream()
                .map(node -> new CodeGraphNode(
                        node.key(), node.type(), node.name(), node.qualifiedName(), node.filePath(), node.chunkId(),
                        provenance(node.metadata(), analyzerId, languageId, authority)))
                .toList();
        List<CodeGraphEdge> edges = graph.edges() == null ? List.of() : graph.edges().stream()
                .map(edge -> new CodeGraphEdge(
                        edge.sourceKey(), edge.targetKey(), edge.type(), edge.confidence(), edge.evidenceChunkId(),
                        provenance(edge.metadata(), analyzerId, languageId, authority)))
                .toList();
        return new CodeGraph(nodes, edges);
    }

    private static Map<String, Object> provenance(
            Map<String, Object> metadata,
            String analyzerId,
            String languageId,
            CodeIntelligenceAuthority authority
    ) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        values.putIfAbsent("codeIntelligenceSchemaVersion", CURRENT_SCHEMA_VERSION);
        values.putIfAbsent("codeIntelligenceAnalyzer", analyzerId);
        values.putIfAbsent("codeIntelligenceLanguage", languageId);
        values.putIfAbsent("codeIntelligenceAuthority", authority.name());
        return Map.copyOf(values);
    }

    private static CodeAnalysisDiagnostic withShadowMetadata(
            CodeAnalysisDiagnostic diagnostic,
            CodeIntelligenceShadowReport shadow,
            String analyzerId,
            String languageId,
            CodeIntelligenceAuthority authority
    ) {
        if (diagnostic == null) return null;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(
                diagnostic.metadata() == null ? Map.of() : diagnostic.metadata());
        metadata.put("codeIntelligenceSchemaVersion", CURRENT_SCHEMA_VERSION);
        metadata.put("codeIntelligenceAnalyzer", analyzerId);
        metadata.put("codeIntelligenceLanguage", languageId);
        metadata.put("codeIntelligenceAuthority", authority.name());
        metadata.put("shadowEquivalent", shadow.equivalent());
        metadata.put("shadowSourceNodes", shadow.sourceNodes());
        metadata.put("shadowIrNodes", shadow.irNodes());
        metadata.put("shadowSourceEdges", shadow.sourceEdges());
        metadata.put("shadowIrEdges", shadow.irEdges());
        return new CodeAnalysisDiagnostic(
                diagnostic.stage(), diagnostic.analyzer(), diagnostic.status(), diagnostic.mode(),
                diagnostic.attemptedFiles(), diagnostic.analyzedFiles(), diagnostic.failedFiles(),
                diagnostic.resolvedRelations(), diagnostic.unresolvedRelations(), diagnostic.nodeCount(),
                diagnostic.edgeCount(), diagnostic.durationMillis(), diagnostic.message(), Map.copyOf(metadata));
    }
}
