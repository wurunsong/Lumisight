package com.lumisight.core.agent.multiagent.model;

import java.util.Map;

public record SubAgentResult(
        String taskId,
        String agentName,
        boolean success,
        String summary,
        java.util.List<String> findings,
        java.util.List<String> evidenceRefs,
        java.util.List<String> suggestedActions,
        double confidence,
        Map<String, Object> payload
) {
}
