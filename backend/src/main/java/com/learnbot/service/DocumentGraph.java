package com.learnbot.service;

import java.util.List;

public record DocumentGraph(List<DocumentGraphNode> nodes, List<DocumentGraphEdge> edges) {
}
