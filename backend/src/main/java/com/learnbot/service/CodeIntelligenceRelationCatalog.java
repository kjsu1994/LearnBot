package com.learnbot.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CodeIntelligenceRelationCatalog {
    private static final List<String> CORE = List.of(
            "DEFINES", "CONTAINS", "CALLS", "REFERENCES", "IMPORTS",
            "EXTENDS", "IMPLEMENTS", "OVERRIDES", "ACCEPTS", "RETURNS", "THROWS",
            "READS_FIELD", "WRITES_FIELD", "ANNOTATED_BY", "INJECTS",
            "DECLARES_BEAN", "TRANSACTION_BOUNDARY", "EXPOSES_ENDPOINT",
            "USES_ENTITY", "MAPS_TO_TABLE", "REPOSITORY_FOR", "QUERIES_ENTITY"
    );
    private static final List<String> EXTENSIONS = List.of(
            "HANDLES_EVENT", "BINDS_TO", "DECLARES_CONTROL", "USES_COMMAND", "COMMAND_EXECUTES",
            "DATA_CONTEXT", "CODE_BEHIND", "PARTIAL_OF", "FILTERS_BY_PROPERTY",
            "COMMAND_BINDING", "COMMAND_TARGETS"
    );
    private static final List<String> ALL;

    static {
        LinkedHashSet<String> relations = new LinkedHashSet<>(CORE);
        relations.addAll(EXTENSIONS);
        ALL = List.copyOf(relations);
    }

    private CodeIntelligenceRelationCatalog() {
    }

    public static List<String> core() {
        return CORE;
    }

    public static List<String> extensions() {
        return EXTENSIONS;
    }

    public static List<String> all() {
        return ALL;
    }

    public static boolean supported(String relation) {
        return relation != null && Set.copyOf(ALL).contains(relation.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
