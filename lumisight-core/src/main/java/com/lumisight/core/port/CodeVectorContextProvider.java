package com.lumisight.core.port;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;

public interface CodeVectorContextProvider {

    List<AgentContextItem> retrieveByCode(String repoRoot, String codeQuery, Integer limit);
}
