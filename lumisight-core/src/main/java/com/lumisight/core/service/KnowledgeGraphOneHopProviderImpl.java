package com.lumisight.core.service;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.KnowledgeGraphOneHopProvider;
import com.lumisight.tools.kg.dto.KnowledgeGraphQueryResult;
import com.lumisight.tools.kg.service.KnowledgeGraphQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;

@Component
@ConditionalOnMissingBean(KnowledgeGraphOneHopProvider.class)
public class KnowledgeGraphOneHopProviderImpl implements KnowledgeGraphOneHopProvider {

    private static final String METHOD_NODE_TYPE = "METHOD";

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
        if (!isMethodNode(result.centerNode())) {
            return List.of(new AgentContextItem(
                    "knowledge_graph_one_hop",
                    String.valueOf(result.centerNode().get("id")),
                    "中心节点不是方法节点，已按限制跳过",
                    Map.of(
                            "repoRoot", result.repoRoot(),
                            "gitCommit", result.gitCommit(),
                            "requiredNodeType", METHOD_NODE_TYPE,
                            "actualNodeType", String.valueOf(result.centerNode().get("nodeType"))
                    )
            ));
        }

        String centerNodeId = String.valueOf(result.centerNode().get("id"));
        List<Map<String, Object>> methodNodes = retrieveMethodNodeLocationsByNodeId(repoRoot, kgNodeId, limit);
        List<Map<String, Object>> methodAdjacentEdges = result.edges().stream()
                .filter(edge -> isMethodNeighbor(repoRoot, centerNodeId, edge))
                .toList();

        String content = "centerNode=" + result.centerNode() + "\n" + "adjacentEdges=" + methodAdjacentEdges;
        return List.of(new AgentContextItem(
                "knowledge_graph_one_hop",
                centerNodeId,
                content,
                Map.of(
                        "repoRoot", result.repoRoot(),
                        "gitCommit", result.gitCommit(),
                        "edgeCount", methodAdjacentEdges.size(),
                        "nodeTypeFilter", METHOD_NODE_TYPE,
                        "methodNodes", methodNodes
                )
        ));
    }

    @Override
    public List<Map<String, Object>> retrieveMethodNodeLocationsByNodeId(String repoRoot, String kgNodeId, Integer limit) {
        KnowledgeGraphQueryResult result = knowledgeGraphQueryService.query(repoRoot, kgNodeId, null, limit);
        if (result.centerNode() == null || !isMethodNode(result.centerNode())) {
            return List.of();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(toMethodNodeLocation(result.centerNode()));
        String centerNodeId = String.valueOf(result.centerNode().get("id"));
        for (Map<String, Object> edge : result.edges()) {
            String from = String.valueOf(edge.get("from"));
            String to = String.valueOf(edge.get("to"));
            String neighborNodeId = centerNodeId.equals(from) ? to : from;
            KnowledgeGraphQueryResult neighbor = knowledgeGraphQueryService.query(repoRoot, neighborNodeId, null, 1);
            if (neighbor.centerNode() != null && isMethodNode(neighbor.centerNode())) {
                nodes.add(toMethodNodeLocation(neighbor.centerNode()));
            }
        }
        return nodes.stream().distinct().toList();
    }

    private boolean isMethodNeighbor(String repoRoot, String centerNodeId, Map<String, Object> edge) {
        String from = String.valueOf(edge.get("from"));
        String to = String.valueOf(edge.get("to"));
        String neighborNodeId = centerNodeId.equals(from) ? to : from;
        KnowledgeGraphQueryResult neighbor = knowledgeGraphQueryService.query(repoRoot, neighborNodeId, null, 1);
        return neighbor.centerNode() != null && isMethodNode(neighbor.centerNode());
    }

    private boolean isMethodNode(Map<String, Object> node) {
        return METHOD_NODE_TYPE.equalsIgnoreCase(String.valueOf(node.get("nodeType")));
    }

    private Map<String, Object> toMethodNodeLocation(Map<String, Object> node) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", String.valueOf(node.get("id")));
        row.put("sourceFile", node.get("sourceFile"));
        row.put("startLine", node.get("startLine"));
        row.put("endLine", node.get("endLine"));
        return row;
    }
}
