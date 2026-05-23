package com.lumisight.tools.kg.model;

public record GraphNode(
        String id,
        NodeType type,
        String name,
        String qualifiedName,
        String sourceFile,
        String repoName,
        String moduleName,
        String packageName,
        String className,
        String methodName,
        Integer parameterCount,
        Integer startLine,
        Integer endLine,
        GraphElementStatus status
) {
}
