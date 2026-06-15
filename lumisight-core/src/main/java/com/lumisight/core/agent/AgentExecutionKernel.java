package com.lumisight.core.agent;

import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.context.ambient.ToolRuntimeScope;
import org.springframework.stereotype.Component;

/**
 * 纯执行内核。
 * application 层完成策略判决之后，kernel 只负责打开运行作用域并驱动统一的 agent loop。
 */
@Component
class AgentExecutionKernel {

    private final AgentExecutionLoop agentExecutionLoop;

    AgentExecutionKernel(AgentExecutionLoop agentExecutionLoop) {
        this.agentExecutionLoop = agentExecutionLoop;
    }

    AgentExecutionLoop.LoopExecutionResult executeLoop(
            PreparedLoopExecution preparedLoopExecution,
            AgentEventPublisher publisher
    ) {
        try (ToolRuntimeScope.Scope toolRuntimeScope = ToolRuntimeScope.open(
                preparedLoopExecution.executionState().resolvedRepoRoot(),
                preparedLoopExecution.executionState().limit(),
                preparedLoopExecution.requestContext().effectiveRequest().userId()
        ); MultiAgentExecutionScope.Scope executionScope = openExecutionScope(preparedLoopExecution.executionScope())) {
            return agentExecutionLoop.run(
                    preparedLoopExecution.requestContext().effectiveRequest(),
                    preparedLoopExecution.executionState().effectiveQuestion(),
                    preparedLoopExecution.skillPlan(),
                    preparedLoopExecution.executionState().contextSession(),
                    preparedLoopExecution.executionState().limit(),
                    preparedLoopExecution.executionState().startRound(),
                    publisher,
                    preparedLoopExecution.requestContext().sessionId(),
                    preparedLoopExecution.requestContext().traceId(),
                    preparedLoopExecution.enabledPermissions(),
                    preparedLoopExecution.executionState().runEpoch(),
                    preparedLoopExecution.relevantMemoryBundle()
            );
        }
    }

    private MultiAgentExecutionScope.Scope openExecutionScope(MultiAgentExecutionScope.Context executionScope) {
        if (executionScope == null) {
            return MultiAgentExecutionScope.open(null);
        }
        return MultiAgentExecutionScope.open(executionScope);
    }
}
