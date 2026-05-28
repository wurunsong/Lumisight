package com.lumisight.core.agent.port;

import com.lumisight.core.agent.model.AgentContextItem;

import java.util.List;

public interface CodeVectorContextProvider {

    List<AgentContextItem> retrieveByCode(String repoRoot, String codeQuery, Integer limit);
}
