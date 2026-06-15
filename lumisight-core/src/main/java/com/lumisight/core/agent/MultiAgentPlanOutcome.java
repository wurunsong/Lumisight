package com.lumisight.core.agent;

import com.lumisight.core.agent.multiagent.service.MultiAgentOrchestrationService;
import com.lumisight.core.context.AgentExecutionState;

/**
 * 多 agent 规划阶段的产物。
 * skipped 表示当前请求继续走单 agent；planned 表示后续可以显式进入子任务调度阶段。
 */
record MultiAgentPlanOutcome(
        AgentExecutionState executionState,
        MultiAgentOrchestrationService.PlannedOrchestration plannedOrchestration
) {
    static MultiAgentPlanOutcome skipped(AgentExecutionState executionState) {
        return new MultiAgentPlanOutcome(executionState, null);
    }

    boolean planned() {
        return plannedOrchestration != null;
    }
}
