package com.lumisight.tools.kg.service;

import com.lumisight.tools.kg.config.NebulaProperties;
import com.lumisight.tools.kg.dto.KnowledgeGraphQueryResult;
import com.lumisight.tools.kg.store.NebulaGraphStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeGraphQueryService {

    private static final int DEFAULT_EDGE_LIMIT = 200;

    private final NebulaProperties nebulaProperties;

    public KnowledgeGraphQueryService(NebulaProperties nebulaProperties) {
        this.nebulaProperties = nebulaProperties;
    }

    public KnowledgeGraphQueryResult query(String repoRoot, String nodeId, String qualifiedName, Integer edgeLimit) {
        Path repo = Path.of(repoRoot).toAbsolutePath().normalize();
        String repoName = repo.getFileName().toString();
        int finalEdgeLimit = edgeLimit == null ? DEFAULT_EDGE_LIMIT : edgeLimit;

        try (NebulaGraphStore store = new NebulaGraphStore(
                nebulaProperties.getHost(),
                nebulaProperties.getPort(),
                nebulaProperties.getUsername(),
                nebulaProperties.getPassword(),
                repoName
        )) {
            String gitCommit = store.currentRepoCommit(repoName);
            Map<String, Object> centerNode = resolveCenterNode(store, nodeId, qualifiedName);
            if (centerNode == null) {
                return new KnowledgeGraphQueryResult(repo.toString(), gitCommit, null, 0, List.of());
            }
            String centerNodeId = String.valueOf(centerNode.get("id"));
            List<Map<String, Object>> edges = store.queryActiveAdjacentEdges(centerNodeId, finalEdgeLimit);
            return new KnowledgeGraphQueryResult(
                    repo.toString(),
                    gitCommit,
                    centerNode,
                    edges.size(),
                    edges
            );
        }
    }

    private Map<String, Object> resolveCenterNode(NebulaGraphStore store, String nodeId, String qualifiedName) {
        if (StringUtils.hasText(nodeId)) {
            return store.queryActiveNodeById(nodeId);
        }
        if (StringUtils.hasText(qualifiedName)) {
            return store.queryActiveNodeByQualifiedName(qualifiedName);
        }
        return null;
    }
}
