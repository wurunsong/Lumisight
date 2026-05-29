package com.lumisight.core.agent.multiagent.model;

import java.util.Map;

public record SubAgentResult(
        String taskId,
        String agentName,
        boolean success,
        String summary,
        Map<String, Object> payload
) {
}
