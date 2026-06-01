package com.lumisight.core.service;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.KnowledgeGraphOneHopProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "lumisight.kg", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopKnowledgeGraphOneHopProvider implements KnowledgeGraphOneHopProvider {

    @Override
    public List<AgentContextItem> retrieveByNodeId(String repoRoot, String kgNodeId, Integer limit) {
        return List.of(new AgentContextItem(
                "knowledge_graph_one_hop",
                kgNodeId == null ? "placeholder" : kgNodeId,
                "Knowledge graph is disabled. Set lumisight.kg.enabled=true to enable one-hop lookup.",
                Map.of("repoRoot", repoRoot)
        ));
    }

    @Override
    public List<Map<String, Object>> retrieveMethodNodeLocationsByNodeId(String repoRoot, String kgNodeId, Integer limit) {
        return List.of();
    }
}
