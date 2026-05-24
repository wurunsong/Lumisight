package com.lumisight.tools.vector.model;

public record VectorIngestResult(
        String collection,
        String documentId,
        String repoRoot,
        String qualifiedName
) {
}
