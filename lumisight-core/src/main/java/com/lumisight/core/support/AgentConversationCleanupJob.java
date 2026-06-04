package com.lumisight.core.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentConversationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationCleanupJob.class);
    private final AgentSessionContextStore conversationManager;

    public AgentConversationCleanupJob(AgentSessionContextStore conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Scheduled(fixedDelayString = "${lumisight.agent.conversation.cleanup-interval-ms:60000}")
    public void cleanup() {
        int removed = conversationManager.purgeExpiredSessions();
        if (removed > 0) {
            log.info("agent conversation cleanup removed expired sessions: {}", removed);
        }
    }
}
