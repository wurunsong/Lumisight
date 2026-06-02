package com.lumisight.core.model;

public record AgentRequest(
        AgentTaskType taskType,
        String repoRoot,
        String question,
        String skillPath,
        String userId,
        String sessionId,
        boolean approveRiskyToolCall,
        boolean interrupt,
        boolean resume,
        boolean includeRagContext,
        boolean includeKnowledgeGraphContext,
        Integer contextLimit,
        AgentRunMode runMode,
        AgentDialogueMode dialogueMode
) {
}
