package com.lumisight.core.agent.multiagent.model;

import java.util.List;
import java.util.Map;

public record MultiAgentExecutionState(
        String orchestrationId,
        String coordinationId,
        String mode,
        AgentOrchestrationMode orchestrationMode,
        OrchestrationPlan plan,
        Map<String, TaskExecutionStatus> taskStates,
        List<SubAgentResult> childSummaries,
        int currentWave,
        Map<String, Object> schedulerState
) {
}
