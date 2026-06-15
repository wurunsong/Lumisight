package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;
import org.springframework.stereotype.Component;

/**
 * 子 agent 复用的空编排策略。
 * 这些执行单元只跑单 agent 内核，不允许再触发新的 multi-agent 编排。
 */
@Component
class NoopMultiAgentExecutionStrategy implements AgentMultiAgentExecutionStrategy {

    @Override
    public MultiAgentPlanOutcome planIfNeeded(
            AgentRequestContext requestContext,
            AgentExecutionState executionState,
            AgentEventPublisher publisher
    ) {
        return MultiAgentPlanOutcome.skipped(executionState);
    }

    @Override
    public MultiAgentOrchestrationOutcome executePlanIfNeeded(
            MultiAgentPlanOutcome planOutcome,
            AgentRequestContext requestContext,
            AgentEventPublisher publisher
    ) {
        AgentExecutionState executionState = planOutcome == null ? null : planOutcome.executionState();
        return MultiAgentOrchestrationOutcome.noop(executionState);
    }
}
