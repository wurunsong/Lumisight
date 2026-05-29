package com.lumisight.core.port;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;
import java.util.Map;

public interface KnowledgeGraphOneHopProvider {

    List<AgentContextItem> retrieveByNodeId(String repoRoot, String kgNodeId, Integer limit);

    List<Map<String, Object>> retrieveMethodNodeLocationsByNodeId(String repoRoot, String kgNodeId, Integer limit);
}
