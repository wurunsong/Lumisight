package com.lumisight.core.agent.multiagent.port;

import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.TaskContextEnvelope;

/**
 * 子 Agent 启动端口。
 * 工具层只依赖这个轻量抽象，避免在工具注册阶段直接拉起完整的 sub-agent 执行链。
 */
public interface SubAgentLauncher {

    SubAgentResult execute(TaskContextEnvelope envelope, String parentSessionId);
}
