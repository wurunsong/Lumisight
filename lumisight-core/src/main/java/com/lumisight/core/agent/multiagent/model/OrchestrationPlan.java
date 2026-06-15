package com.lumisight.core.agent.multiagent.model;

import java.util.List;
import java.util.Map;

public record OrchestrationPlan(
        String planId,
        String goal,
        List<SubAgentTask> tasks,
        AgentOrchestrationMode orchestrationMode,
        String outputContract,
        Map<String, Object> metadata
) {
}
