package com.lumisight.core.context.ambient;
/**
 * 多agent调度上下文
 * todo 后面抽象一个AgentAmbientContext接口，作为非agent运行上下文的基类
 */
import com.lumisight.core.model.AgentRequest;

import java.util.Map;

public record OrchestrationContext(
        String orchestrationId,
        AgentRequest request,
        Map<String, Object> runtimeAttributes
) {
}
