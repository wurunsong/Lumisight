package com.lumisight.core.agent.service;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.KnowledgeGraphOneHopProvider;
import com.lumisight.tools.kg.dto.KnowledgeGraphQueryResult;
import com.lumisight.tools.kg.service.KnowledgeGraphQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnMissingBean(KnowledgeGraphOneHopProvider.class)
public class KnowledgeGraphOneHopProviderImpl implements KnowledgeGraphOneHopProvider {

    private final KnowledgeGraphQueryService knowledgeGraphQueryService;

    public KnowledgeGraphOneHopProviderImpl(KnowledgeGraphQueryService knowledgeGraphQueryService) {
        this.knowledgeGraphQueryService = knowledgeGraphQueryService;
    }

    @Override
    public List<AgentContextItem> retrieveByNodeId(String repoRoot, String kgNodeId, Integer limit) {
        KnowledgeGraphQueryResult result = knowledgeGraphQueryService.query(repoRoot, kgNodeId, null, limit);
        if (result.centerNode() == null) {
            return List.of(new AgentContextItem(
                    "knowledge_graph_one_hop",
                    kgNodeId,
                    "未找到对应图谱节点",
                    Map.of("repoRoot", repoRoot, "kgNodeId", kgNodeId)
            ));
        }

        String content = "centerNode=" + result.centerNode() + "\n" + "adjacentEdges=" + result.edges();
        return List.of(new AgentContextItem(
                "knowledge_graph_one_hop",
                String.valueOf(result.centerNode().get("id")),
                content,
                Map.of(
                        "repoRoot", result.repoRoot(),
                        "gitCommit", result.gitCommit(),
                        "edgeCount", result.edgeCount()
                )
        ));
    }
}
