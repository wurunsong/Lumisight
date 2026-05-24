package com.lumisight.tools.vector.model;

public record RepoCodeChunkIngestResult(
        String collection,
        String repoRoot,
        String baselineCommit,
        String currentCommit,
        int javaFileCount,
        int methodCount,
        int chunkCount,
        int deletedFileCount
) {
}
