package com.lumisight.core.agent.port;

import com.lumisight.core.agent.model.AgentContextItem;

import java.util.List;

public interface KnowledgeGraphOneHopProvider {

    List<AgentContextItem> retrieveByNodeId(String repoRoot, String kgNodeId, Integer limit);
}
