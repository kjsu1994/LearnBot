package com.learnbot.service;

import java.util.List;

public record CodeGraph(
        List<CodeGraphNode> nodes,
        List<CodeGraphEdge> edges
) {
}
