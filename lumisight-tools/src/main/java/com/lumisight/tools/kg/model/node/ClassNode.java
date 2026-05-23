package com.lumisight.tools.kg.model.node;

import com.lumisight.tools.kg.model.GraphElementStatus;
import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.NodeType;
import com.lumisight.tools.kg.util.NodeIdUtils;

public record ClassNode(
        String moduleName,
        String packageName,
        String className,
        String qualifiedClassName,
        String sourceFile,
        String repoName
) implements KgNode {

    @Override
    public GraphNode toGraphNode() {
        return new GraphNode(
                NodeIdUtils.nodeId(NodeType.CLASS, qualifiedClassName),
                NodeType.CLASS,
                className,
                qualifiedClassName,
                sourceFile,
                repoName,
                moduleName,
                packageName,
                className,
                null,
                null,
                null,
                null,
                GraphElementStatus.ACTIVE
        );
    }
}
