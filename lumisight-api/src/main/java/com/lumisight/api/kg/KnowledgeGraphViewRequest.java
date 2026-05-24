package com.lumisight.api.kg;

public record KnowledgeGraphViewRequest(
        String repoRoot,
        Integer nodeLimit,
        Integer edgeLimit
) {
}
