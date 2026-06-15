package com.lumisight.core.agent;

/**
 * 线程池中的统一执行单元。
 * 中间可以有复杂的规划/拆解/调度，但最后都必须收敛成 task info + loop handler。
 */
public record AgentLoopTask<R>(
        String taskId,
        String taskKind,
        AgentLoopTaskHandler<R> handler
) {
}
