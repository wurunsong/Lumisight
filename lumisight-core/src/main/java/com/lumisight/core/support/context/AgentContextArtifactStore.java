package com.lumisight.core.support.context;

import java.util.Map;

public interface AgentContextArtifactStore {

    AgentContextArtifactRef persist(String sessionId, String entryId, String content, Map<String, Object> metadata);

    String load(AgentContextArtifactRef artifactRef);
}
