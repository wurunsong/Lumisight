package com.lumisight.api.agent.ws.dto;

import com.lumisight.core.model.AgentEvent;

public record WsAgentMessage(
        String type,
        String requestId,
        AgentEvent event,
        String message,
        Long timestamp
) {
}

