package com.lumisight.core.config;

import com.lumisight.common.concurrent.ThreadContextRegistry;
import com.lumisight.core.context.ambient.ToolInvocationScope;
import com.lumisight.core.context.ambient.ToolRuntimeScope;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ThreadContextConfiguration {

    @PostConstruct
    public void registerThreadContextCarriers() {
        ThreadContextRegistry.register(ThreadContextRegistry.ContextCarrier.of(
                "agentToolRuntimeContext",
                ToolRuntimeScope::current,
                ToolRuntimeScope::restore,
                ToolRuntimeScope::clear
        ));
        ThreadContextRegistry.register(ThreadContextRegistry.ContextCarrier.of(
                "agentToolInvocationContext",
                ToolInvocationScope::current,
                ToolInvocationScope::restore,
                ToolInvocationScope::clear
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
