package com.lumisight.core.agent;

import com.lumisight.core.model.AgentRequest;

/**
 * 执行内核的输入命令对象。
 * 入口层只负责组装 command，内核只消费 command，不再依赖具体入口来源。
 */
public record AgentExecutionCommand(
        AgentRequest request,
        String traceId,
        String sessionId,
        AgentExecutionProfile profile
) {
}
