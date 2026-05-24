package com.lumisight.api.kg;

public record CodeChunkIngestRequest(
        String repoRoot,
        String sourceFile,
        String qualifiedName,
        String chunkText,
        Integer startLine,
        Integer endLine,
        String gitBranch,
        String gitCommit
) {
}
