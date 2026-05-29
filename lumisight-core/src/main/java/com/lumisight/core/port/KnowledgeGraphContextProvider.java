package com.lumisight.core.port;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;

public interface KnowledgeGraphContextProvider {

    List<AgentContextItem> retrieve(String repoRoot, String question, Integer limit);
}
