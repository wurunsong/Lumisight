package com.lumisight.core.context.ambient;

import com.lumisight.core.model.AgentRequest;

import java.util.Map;

/**
 * 多 agent 调度输入，描述一次编排请求本身，不属于线程绑定的 AmbientScope。
 */
public record OrchestrationContext(
        String orchestrationId,
        AgentRequest request,
        Map<String, Object> runtimeAttributes
) {
}
