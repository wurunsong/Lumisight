package com.lumisight.tools.vector.model;

public record CodeChunkIngestCommand(
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
