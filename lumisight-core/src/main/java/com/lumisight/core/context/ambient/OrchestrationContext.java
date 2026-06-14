package com.lumisight.core.context.ambient;

import com.lumisight.core.model.AgentRequest;

import java.util.Map;

/**
 * 多agent调度上下文
 * todo 后面抽象一个AgentRuntimeScope接口，作为非agent运行作用域的基类
 */
public record OrchestrationContext(
        String orchestrationId,
        AgentRequest request,
        Map<String, Object> runtimeAttributes
) {
}
