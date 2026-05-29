package com.lumisight.core.agent.model;

public record AgentRequest(
        AgentTaskType taskType,
        String repoRoot,
        String question,
        boolean includeRagContext,
        boolean includeKnowledgeGraphContext,
        Integer contextLimit,
        AgentRunMode runMode,
        AgentDialogueMode dialogueMode
) {
}
