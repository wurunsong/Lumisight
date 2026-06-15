package com.lumisight.core.agent;

/**
 * 线程池中纯 loop 执行完成后的结果句柄。
 * final answer、事件汇总等 application 层收尾逻辑会在线程池外继续处理。
 */
public final class CompletedAgentLoopTask {

    private final String taskId;
    private final PreparedLoopExecution preparedLoopExecution;
    private final AgentEventPublisher publisher;
    private final AgentExecutionLoop.LoopExecutionResult loopResult;

    CompletedAgentLoopTask(
            String taskId,
            PreparedLoopExecution preparedLoopExecution,
            AgentEventPublisher publisher,
            AgentExecutionLoop.LoopExecutionResult loopResult
    ) {
        this.taskId = taskId;
        this.preparedLoopExecution = preparedLoopExecution;
        this.publisher = publisher;
        this.loopResult = loopResult;
    }

    public String taskId() {
        return taskId;
    }

    PreparedLoopExecution preparedLoopExecution() {
        return preparedLoopExecution;
    }

    AgentEventPublisher publisher() {
        return publisher;
    }

    AgentExecutionLoop.LoopExecutionResult loopResult() {
        return loopResult;
    }
}
