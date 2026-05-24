package com.lumisight.tools.kg.service;

import com.lumisight.tools.kg.config.NebulaProperties;
import com.lumisight.tools.kg.store.NebulaGraphStore;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeGraphViewService {

    private static final int DEFAULT_NODE_LIMIT = 500;
    private static final int DEFAULT_EDGE_LIMIT = 1000;

    private final NebulaProperties nebulaProperties;

    public KnowledgeGraphViewService(NebulaProperties nebulaProperties) {
        this.nebulaProperties = nebulaProperties;
    }

    public KnowledgeGraphViewResult view(String repoRoot, Integer nodeLimit, Integer edgeLimit) {
        Path repo = Path.of(repoRoot).toAbsolutePath().normalize();
        String repoName = repo.getFileName().toString();
        int finalNodeLimit = nodeLimit == null ? DEFAULT_NODE_LIMIT : nodeLimit;
        int finalEdgeLimit = edgeLimit == null ? DEFAULT_EDGE_LIMIT : edgeLimit;

        try (NebulaGraphStore store = new NebulaGraphStore(
                nebulaProperties.getHost(),
                nebulaProperties.getPort(),
                nebulaProperties.getUsername(),
                nebulaProperties.getPassword(),
                repoName
        )) {
            String gitCommit = store.currentRepoCommit(repoName);
            List<Map<String, Object>> nodes = store.queryActiveNodes(finalNodeLimit);
            List<Map<String, Object>> edges = store.queryActiveEdges(finalEdgeLimit);
            return new KnowledgeGraphViewResult(
                    repo.toString(),
                    gitCommit,
                    nodes.size(),
                    edges.size(),
                    nodes,
                    edges
            );
        }
    }
}
