package com.lumisight.tools.kg.dto;

import java.util.List;
import java.util.Map;

public record KnowledgeGraphQueryResult(
        String repoRoot,
        String gitCommit,
        Map<String, Object> centerNode,
        int edgeCount,
        List<Map<String, Object>> edges
) {
}
