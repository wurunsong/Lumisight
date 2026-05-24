package com.lumisight.tools.kg.service;

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
