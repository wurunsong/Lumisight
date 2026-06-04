package com.lumisight.api.agent.dto.response;

import java.util.List;
import java.util.Map;

public record AgentCronJobResponse(
        String jobId,
        String name,
        String cron,
        boolean enabled,
        String timezone,
        String sessionId,
        String userId,
        String question,
        long createdAt,
        long updatedAt,
        Long lastTriggeredAt,
        String lastStatus,
        String lastMessage,
        List<AgentCronRunResponse> recentRuns,
        Map<String, Object> metadata
) {
}
