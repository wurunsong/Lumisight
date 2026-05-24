package com.lumisight.tools.vector.model;

public record RepoCodeChunkIngestResult(
        String collection,
        String repoRoot,
        int javaFileCount,
        int methodCount,
        int chunkCount
) {
}
