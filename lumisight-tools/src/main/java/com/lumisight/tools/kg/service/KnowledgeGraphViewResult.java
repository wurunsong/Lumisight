package com.lumisight.tools.kg.service;

import java.util.List;
import java.util.Map;

public record KnowledgeGraphViewResult(
        String repoRoot,
        String gitCommit,
        int nodeCount,
        int edgeCount,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges
) {
}
