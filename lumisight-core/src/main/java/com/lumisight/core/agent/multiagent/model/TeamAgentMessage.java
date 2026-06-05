package com.lumisight.core.agent.multiagent.model;

import java.util.Map;

public record TeamAgentMessage(
        String messageId,
        String teamId,
        String fromAgentId,
        String toAgentId,
        TeamAgentMessageType type,
        String content,
        Map<String, Object> payload,
        long timestamp
) {
}
