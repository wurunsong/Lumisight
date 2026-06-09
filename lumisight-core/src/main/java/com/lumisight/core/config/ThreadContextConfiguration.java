package com.lumisight.core.config;

import com.lumisight.common.concurrent.ThreadContextRegistry;
import com.lumisight.core.context.ambient.AgentToolInvocationContext;
import com.lumisight.core.context.ambient.AgentToolRuntimeContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ThreadContextConfiguration {

    @PostConstruct
    public void registerThreadContextCarriers() {
        ThreadContextRegistry.register(ThreadContextRegistry.ContextCarrier.of(
                "agentToolRuntimeContext",
                AgentToolRuntimeContext::current,
                AgentToolRuntimeContext::restore,
                AgentToolRuntimeContext::clear
        ));
        ThreadContextRegistry.register(ThreadContextRegistry.ContextCarrier.of(
                "agentToolInvocationContext",
                AgentToolInvocationContext::current,
                AgentToolInvocationContext::restore,
                AgentToolInvocationContext::clear
        ));
        ThreadContextRegistry.register(ThreadContextRegistry.ContextCarrier.of(
                "slf4jMdc",
                MDC::getCopyOfContextMap,
                contextMap -> {
                    if (contextMap == null || contextMap.isEmpty()) {
                        MDC.clear();
                        return;
                    }
                    MDC.setContextMap(contextMap);
                },
                MDC::clear
        ));
    }
}
