package com.lumisight.tools.kg.model.node;

import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.NodeType;
import com.lumisight.tools.kg.util.NodeIdUtils;

public record MethodNode(
        String moduleName,
        String packageName,
        String className,
        String methodName,
        int parameterCount,
        String qualifiedMethodName,
        Integer startLine,
        Integer endLine,
        String sourceFile,
        String repoName
) implements KgNode {

    @Override
    public GraphNode toGraphNode() {
        return new GraphNode(
                NodeIdUtils.nodeId(NodeType.METHOD, qualifiedMethodName),
                NodeType.METHOD,
                methodName,
                qualifiedMethodName,
                sourceFile,
                repoName,
                moduleName,
                packageName,
                className,
                methodName,
                parameterCount,
                startLine,
                endLine
        );
    }
}
