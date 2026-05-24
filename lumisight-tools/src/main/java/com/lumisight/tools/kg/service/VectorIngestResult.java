package com.lumisight.tools.kg.service;

public record VectorIngestResult(
        String collection,
        String documentId,
        String repoRoot,
        String qualifiedName
) {
}
