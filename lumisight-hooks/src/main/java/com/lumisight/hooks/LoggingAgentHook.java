package com.lumisight.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingAgentHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(LoggingAgentHook.class);

    @Override
    public void onHook(AgentHookPoint point, AgentHookContext context) {
        log.info("agent_hook point={}, sessionId={}, round={}, tool={}",
                point, context.sessionId(), context.round(), context.toolName());
    }
}
