package com.lumisight.api.vector.dto.request;

public record CodeChunkAutoIngestRequest(
        String repoRoot,
        String sourceFile,
        String qualifiedName,
        String codeText,
        Integer maxChunkChars,
        Integer overlapChars,
        String gitBranch,
        String gitCommit
) {
}
