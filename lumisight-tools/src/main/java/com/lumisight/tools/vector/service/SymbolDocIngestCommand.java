package com.lumisight.tools.vector.service;

public record SymbolDocIngestCommand(
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
