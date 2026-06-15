package com.lumisight.core.agent;

/**
 * 标识当前执行单元在整个 agent 体系里的运行身份。
 * 这里描述的是“谁在执行”，不是 single / multi 这种整体运行模式。
 */
public enum AgentExecutionKind {
    PRIMARY,
    SUB_AGENT
}
