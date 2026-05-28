package com.lumisight.core.agent.port;

import com.lumisight.core.agent.model.AgentContextItem;

import java.util.List;

public interface KnowledgeGraphContextProvider {

    List<AgentContextItem> retrieve(String repoRoot, String question, Integer limit);
}
