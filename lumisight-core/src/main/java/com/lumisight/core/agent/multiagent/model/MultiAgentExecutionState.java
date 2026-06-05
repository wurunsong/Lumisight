package com.lumisight.core.agent.multiagent.model;

import java.util.List;
import java.util.Map;

public record MultiAgentExecutionState(
        String orchestrationId,
        String mode,
        TopologyType topology,
        OrchestrationPlan plan,
        Map<String, TaskExecutionStatus> taskStates,
        List<SubAgentResult> childSummaries,
        Map<String, Long> inboxOffsets,
        int currentRound,
        Map<String, Object> fallbackState
) {
}
