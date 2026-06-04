package com.lumisight.api.agent.dto.response;

public record AgentCronRunResponse(
        String runId,
        long triggeredAt,
        Long completedAt,
        String status,
        String finalMessage,
        String errorMessage
) {
}
