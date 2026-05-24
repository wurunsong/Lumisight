package com.lumisight.api.kg;

public record KnowledgeGraphQueryRequest(
        String repoRoot,
        String nodeId,
        String qualifiedName,
        Integer edgeLimit
) {
}
