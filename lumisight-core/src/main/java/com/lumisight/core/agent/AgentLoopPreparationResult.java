package com.lumisight.core.agent;

/**
 * application 层准备 loop 的结果。
 * 如果准备阶段已经因为 human gate、interrupt 或 plan mode 收口，就不会再生成 loop 输入。
 */
record AgentLoopPreparationResult(
        AgentExecutionResult completedResult,
        PreparedLoopExecution preparedLoopExecution
) {

    static AgentLoopPreparationResult completed(AgentExecutionResult completedResult) {
        return new AgentLoopPreparationResult(completedResult, null);
    }

    static AgentLoopPreparationResult ready(PreparedLoopExecution preparedLoopExecution) {
        return new AgentLoopPreparationResult(null, preparedLoopExecution);
    }

    boolean completed() {
        return completedResult != null;
    }
}
