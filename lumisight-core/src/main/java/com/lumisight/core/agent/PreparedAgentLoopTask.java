package com.lumisight.core.agent;

/**
 * 已完成 application 层准备的 loop task 句柄。
 * 准备阶段会把真正可提交线程池的 AgentLoopTask 一起封好，下游只负责透传执行。
 */
public final class PreparedAgentLoopTask {

    private final String taskId;
    private final String taskKind;
    private final AgentEventPublisher publisher;
    private final AgentExecutionResult completedResult;
    private final AgentLoopTask<CompletedAgentLoopTask> executableTask;

    private PreparedAgentLoopTask(
            String taskId,
            String taskKind,
            AgentEventPublisher publisher,
            AgentExecutionResult completedResult,
            AgentLoopTask<CompletedAgentLoopTask> executableTask
    ) {
        this.taskId = taskId;
        this.taskKind = taskKind;
        this.publisher = publisher;
        this.completedResult = completedResult;
        this.executableTask = executableTask;
    }

    static PreparedAgentLoopTask completed(
            String taskId,
            String taskKind,
            AgentEventPublisher publisher,
            AgentExecutionResult completedResult
    ) {
        return new PreparedAgentLoopTask(taskId, taskKind, publisher, completedResult, null);
    }

    static PreparedAgentLoopTask ready(
            String taskId,
            String taskKind,
            AgentEventPublisher publisher,
            AgentLoopTask<CompletedAgentLoopTask> executableTask
    ) {
        return new PreparedAgentLoopTask(taskId, taskKind, publisher, null, executableTask);
    }

    public String taskId() {
        return taskId;
    }

    public String taskKind() {
        return taskKind;
    }

    public boolean completedBeforeLoop() {
        return completedResult != null;
    }

    AgentExecutionResult completedResult() {
        return completedResult;
    }

    AgentEventPublisher publisher() {
        return publisher;
    }

    AgentLoopTask<CompletedAgentLoopTask> executableTask() {
        if (executableTask == null) {
            throw new IllegalStateException("prepared task already completed before loop: " + taskId);
        }
        return executableTask;
    }
}
