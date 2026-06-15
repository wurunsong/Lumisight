package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;
import com.lumisight.core.context.ambient.MultiAgentExecutionScope;

/**
 * 多 agent 编排完成后返回给应用层的收敛结果。
 */
record MultiAgentOrchestrationOutcome(
        AgentExecutionState executionState,
        MultiAgentExecutionScope.Context leadConvergenceScope
) {
    static MultiAgentOrchestrationOutcome noop(AgentExecutionState executionState) {
        return new MultiAgentOrchestrationOutcome(executionState, null);
    }
}
