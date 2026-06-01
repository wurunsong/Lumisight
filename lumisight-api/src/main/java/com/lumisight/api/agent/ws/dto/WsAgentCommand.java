package com.lumisight.api.agent.ws.dto;

import com.lumisight.api.agent.dto.request.AgentRunRequest;

public record WsAgentCommand(
        String type,
        String requestId,
        AgentRunRequest request
) {
}

