package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;

/**
 * 多 agent 编排策略。
 * primary 请求使用真实编排；子 agent 使用 no-op 策略，避免递归回调入口。
 */
interface AgentMultiAgentExecutionStrategy {

    MultiAgentOrchestrationOutcome orchestrateIfNeeded(
            AgentRequestContext requestContext,
            AgentExecutionState executionState,
            AgentEventPublisher publisher
    );
}
