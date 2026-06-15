package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;

/**
 * 应用层完成“准备 + 决策”之后交给执行内核的稳定输入。
 * 到了这一步，请求走 single 还是 multi 已经确定，kernel 只负责真正执行。
 */
record PreparedAgentExecution(
        AgentExecutionCommand command,
        AgentExecutionState executionState,
        AgentRequestContext requestContext
) {
}
