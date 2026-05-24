package com.lumisight.api.kg.dto.request;

public record KnowledgeGraphViewRequest(
        String repoRoot,
        Integer nodeLimit,
        Integer edgeLimit
) {
}
