package com.lumisight.tools.kg.util;

import com.lumisight.tools.kg.model.EdgeType;
import com.lumisight.tools.kg.model.NodeType;

public final class NodeIdUtils {

    private NodeIdUtils() {
    }

    public static String nodeId(NodeType type, String qualifiedName) {
        return type.name() + ":" + qualifiedName;
    }

    public static String edgeId(String fromNodeId, String toNodeId, EdgeType type) {
        return type.name() + ":" + fromNodeId + "->" + toNodeId;
    }
}
