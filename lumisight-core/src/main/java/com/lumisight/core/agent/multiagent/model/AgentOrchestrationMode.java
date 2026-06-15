package com.lumisight.core.agent.multiagent.model;

/**
 * Agent 编排方式。
 * wave 只是执行这些编排方式时切出来的调度批次，不属于编排方式本身。
 */
public enum AgentOrchestrationMode {
    SERIAL,
    PARALLEL,
    HYBRID
}
