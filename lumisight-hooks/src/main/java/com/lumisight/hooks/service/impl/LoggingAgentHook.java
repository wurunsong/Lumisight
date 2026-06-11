package com.lumisight.hooks.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.lumisight.hooks.dto.AgentHookContext;
import com.lumisight.hooks.enums.AgentHookPoint;
import com.lumisight.hooks.service.AgentHook;

@Component
public class LoggingAgentHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(LoggingAgentHook.class);

    @Override
    public void onHook(AgentHookPoint point, AgentHookContext context) {
        log.info("agent_hook point={}, sessionId={}, round={}, tool={}",
                point, context.sessionId(), context.round(), context.toolName());
    }
}
