package com.lumisight.core.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.agent.conversation")
public class AgentConversationProperties {

    private long waitingUserTtlSeconds = 900;
    private long waitingGateTtlSeconds = 1800;

    public long getWaitingUserTtlSeconds() {
        return waitingUserTtlSeconds;
    }

    public void setWaitingUserTtlSeconds(long waitingUserTtlSeconds) {
        this.waitingUserTtlSeconds = waitingUserTtlSeconds;
    }

    public long getWaitingGateTtlSeconds() {
        return waitingGateTtlSeconds;
    }

    public void setWaitingGateTtlSeconds(long waitingGateTtlSeconds) {
        this.waitingGateTtlSeconds = waitingGateTtlSeconds;
    }
}

