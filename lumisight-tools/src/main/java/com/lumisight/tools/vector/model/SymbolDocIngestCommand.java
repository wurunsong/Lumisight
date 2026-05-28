package com.lumisight.tools.vector.model;

public record SymbolDocIngestCommand(
        String repoRoot,
        String sourceFile,
        String qualifiedName,
        String kgNodeId,
        String symbolSignature,
        String codeContext,
        String symbolDocText,
        String gitBranch,
        String gitCommit
) {
}
