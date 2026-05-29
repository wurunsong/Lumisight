package com.lumisight.core.agent.model;

public record AgentRequest(
        AgentTaskType taskType,
        String repoRoot,
        String question,
        String sessionId,
        String followUpAnswer,
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
