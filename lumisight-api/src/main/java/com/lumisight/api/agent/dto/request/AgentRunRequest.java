package com.lumisight.api.agent.dto.request;

public record AgentRunRequest(
        String taskType,
        String repoRoot,
        String question,
        Boolean includeRagContext,
        Boolean includeKnowledgeGraphContext,
        Integer contextLimit,
        String runMode
) {
}
