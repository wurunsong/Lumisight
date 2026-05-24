package com.lumisight.tools.kg.service;

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
