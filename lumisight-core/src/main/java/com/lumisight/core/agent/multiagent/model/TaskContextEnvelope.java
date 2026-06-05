package com.lumisight.core.agent.multiagent.model;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;
import java.util.Map;

public record TaskContextEnvelope(
        String taskId,
        String goal,
        String instruction,
        SubAgentCapability capability,
        String repoRoot,
        List<AgentContextItem> inputEvidence,
        Map<String, Object> constraints,
        String expectedOutputSchema,
        Map<String, Object> budget
) {
}
