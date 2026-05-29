package com.lumisight.core.agent.multiagent.model;

import com.lumisight.core.agent.model.AgentRequest;

import java.util.Map;

public record OrchestrationContext(
        String orchestrationId,
        AgentRequest request,
        Map<String, Object> runtimeAttributes
) {
}
