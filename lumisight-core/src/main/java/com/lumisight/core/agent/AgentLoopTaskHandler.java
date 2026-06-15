package com.lumisight.core.agent;

/**
 * 统一的 agent loop 任务处理接口。
 * single-agent 和 sub-agent 可以有不同实现细节，但最终都要收敛成同一种 handler 提交给线程池。
 */
@FunctionalInterface
public interface AgentLoopTaskHandler<R> {

    R execute() throws Exception;
}
