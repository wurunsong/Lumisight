package com.lumisight.core.port;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;

public interface SourceCodeLookupProvider {

    List<AgentContextItem> lookupMethodSource(String repoRoot, String sourceFile, Integer startLine, Integer endLine);
}
