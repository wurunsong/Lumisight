package com.lumisight.core.agent;

/**
 * 单次执行的策略画像。
 * 通过显式 profile 控制是否允许自动升级、多 agent 编排等行为，避免子 agent 误走主入口逻辑。
 */
public record AgentExecutionProfile(
        AgentExecutionKind kind,
        boolean allowRunModeResolution,
        boolean allowMultiAgentOrchestration
) {

    public static AgentExecutionProfile primary() {
        return new AgentExecutionProfile(AgentExecutionKind.PRIMARY, true, true);
    }

    public static AgentExecutionProfile subAgent() {
        return new AgentExecutionProfile(AgentExecutionKind.SUB_AGENT, false, false);
    }
}
