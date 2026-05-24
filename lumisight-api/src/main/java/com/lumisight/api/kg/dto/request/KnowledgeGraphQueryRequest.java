package com.lumisight.api.kg.dto.request;

public record KnowledgeGraphQueryRequest(
        String repoRoot,
        String nodeId,
        String qualifiedName,
        Integer edgeLimit
) {
}
