package com.lumisight.api.agent.dto.request;

public record AgentRunRequest(
        String taskType,
        String repoRoot,
        String question,
        String skillPath,
        String sessionId,
        Boolean approveRiskyToolCall,
        Boolean interrupt,
        Boolean resume,
        Boolean includeRagContext,
        Boolean includeKnowledgeGraphContext,
        Integer contextLimit,
        String runMode,
        String dialogueMode
) {
}
