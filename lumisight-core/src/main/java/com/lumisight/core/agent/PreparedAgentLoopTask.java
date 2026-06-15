package com.lumisight.core.agent;

/**
 * 已完成 application 层准备的 loop task 句柄。
 * 对外只暴露任务身份，内部保留 publisher / prepared loop 等细节，避免 sub-agent 调度层依赖 kernel 类型。
 */
public final class PreparedAgentLoopTask {

    private final String taskId;
    private final String taskKind;
    private final AgentEventPublisher publisher;
    private final AgentLoopPreparationResult preparationResult;

    PreparedAgentLoopTask(
            String taskId,
            String taskKind,
            AgentEventPublisher publisher,
            AgentLoopPreparationResult preparationResult
    ) {
        this.taskId = taskId;
        this.taskKind = taskKind;
        this.publisher = publisher;
        this.preparationResult = preparationResult;
    }

    public String taskId() {
        return taskId;
    }

    public String taskKind() {
        return taskKind;
    }

    public boolean completedBeforeLoop() {
        return preparationResult.completed();
    }

    AgentEventPublisher publisher() {
        return publisher;
    }

    AgentLoopPreparationResult preparationResult() {
        return preparationResult;
    }
}
