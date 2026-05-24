package com.lumisight.tools.vector.service;

public record VectorIngestResult(
        String collection,
        String documentId,
        String repoRoot,
        String qualifiedName
) {
}
