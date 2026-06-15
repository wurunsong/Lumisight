package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;

/**
 * 执行内核的结构化结果。
 */
public record AgentExecutionResult(
        AgentExecutionState executionState,
        AgentExecutionStatus status
) {
}
