package com.lumisight.tools.kg.model.node;

import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.NodeType;
import com.lumisight.tools.kg.util.NodeIdUtils;

public record PackageNode(
        String moduleName,
        String packageName,
        String sourceFile,
        String repoName
) implements KgNode {

    @Override
    public GraphNode toGraphNode() {
        String qn = moduleName + ":" + packageName;
        return new GraphNode(
                NodeIdUtils.nodeId(NodeType.PACKAGE, qn),
                NodeType.PACKAGE,
                packageName,
                qn,
                sourceFile,
                repoName,
                moduleName,
                packageName,
                null,
                null,
                null
        );
    }
}
