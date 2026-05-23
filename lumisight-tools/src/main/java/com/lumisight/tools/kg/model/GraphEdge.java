package com.lumisight.tools.kg.model;

public record GraphEdge(
        String id,
        String fromNodeId,
        String toNodeId,
        EdgeType type,
        String sourceFile,
        GraphElementStatus status
) {
}
