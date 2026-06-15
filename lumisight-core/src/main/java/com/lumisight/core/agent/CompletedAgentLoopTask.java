package com.lumisight.core.agent;

/**
 * 线程池中纯 loop 执行完成后的结果句柄。
 * final answer、事件汇总等 application 层收尾逻辑会在线程池外继续处理。
 */
public final class CompletedAgentLoopTask {

    private final PreparedAgentLoopTask preparedTask;
    private final AgentExecutionLoop.LoopExecutionResult loopResult;

    CompletedAgentLoopTask(
            PreparedAgentLoopTask preparedTask,
            AgentExecutionLoop.LoopExecutionResult loopResult
    ) {
        this.preparedTask = preparedTask;
        this.loopResult = loopResult;
    }

    public String taskId() {
        return preparedTask.taskId();
    }

    PreparedAgentLoopTask preparedTask() {
        return preparedTask;
    }

    AgentExecutionLoop.LoopExecutionResult loopResult() {
        return loopResult;
    }
}
