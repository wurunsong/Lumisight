package com.lumisight.core.agent.port;

import com.lumisight.core.agent.model.AgentContextItem;

import java.util.List;

public interface SourceCodeLookupProvider {

    List<AgentContextItem> lookupMethodSource(String repoRoot, String sourceFile, Integer startLine, Integer endLine);
}
