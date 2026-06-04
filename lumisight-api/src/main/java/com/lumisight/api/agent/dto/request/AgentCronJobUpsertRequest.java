package com.lumisight.api.agent.dto.request;

public record AgentCronJobUpsertRequest(
        String name,
        String cron,
        Boolean enabled,
        AgentRunRequest request
) {
}
