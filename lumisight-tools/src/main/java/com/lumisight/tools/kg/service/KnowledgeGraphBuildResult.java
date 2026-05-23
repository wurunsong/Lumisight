package com.lumisight.tools.kg.service;

public record KnowledgeGraphBuildResult(
        String repoRoot,
        int nodeCount,
        int edgeCount,
        int indexedFileCount,
        String generatedAt,
        boolean updated,
        String skipReason,
        String gitBranch,
        String gitCommit
) {
}
