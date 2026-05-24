package com.lumisight.api.vector.dto.request;

public record SymbolDocIngestRequest(
        String repoRoot,
        String sourceFile,
        String qualifiedName,
        String symbolSignature,
        String codeContext,
        String symbolDocText,
        String gitBranch,
        String gitCommit
) {
}
