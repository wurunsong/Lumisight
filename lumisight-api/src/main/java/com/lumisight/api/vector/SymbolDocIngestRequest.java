package com.lumisight.api.vector;

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
