package com.lumisight.core.agent;

import com.lumisight.core.model.AgentRequest;

/**
 * 一次执行链路里的稳定请求上下文。
 */
record AgentRequestContext(
        AgentRequest effectiveRequest,
        String traceId,
        String sessionId,
        AgentExecutionProfile profile
) {
}
