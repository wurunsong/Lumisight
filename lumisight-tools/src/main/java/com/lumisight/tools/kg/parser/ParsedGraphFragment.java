package com.lumisight.tools.kg.parser;

import com.lumisight.tools.kg.model.GraphEdge;
import com.lumisight.tools.kg.model.GraphNode;

import java.util.LinkedHashMap;
import java.util.Map;

public class ParsedGraphFragment {

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final Map<String, GraphEdge> edges = new LinkedHashMap<>();

    public Map<String, GraphNode> getNodes() {
        return nodes;
    }

    public Map<String, GraphEdge> getEdges() {
        return edges;
    }

    public void merge(ParsedGraphFragment other) {
        this.nodes.putAll(other.getNodes());
        this.edges.putAll(other.getEdges());
    }
}
