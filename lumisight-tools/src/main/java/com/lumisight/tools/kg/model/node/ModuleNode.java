package com.lumisight.tools.kg.model.node;

import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.NodeType;
import com.lumisight.tools.kg.util.NodeIdUtils;

public record ModuleNode(
        String moduleName,
        String sourceFile,
        String repoName
) implements KgNode {

    @Override
    public GraphNode toGraphNode() {
        String qn = moduleName;
        return new GraphNode(
                NodeIdUtils.nodeId(NodeType.MODULE, qn),
                NodeType.MODULE,
                moduleName,
                qn,
                sourceFile,
                repoName,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
