package com.lumisight.api.agent.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final AgentWebSocketProperties webSocketProperties;

    public AgentWebSocketConfig(
            AgentWebSocketHandler agentWebSocketHandler,
            AgentWebSocketProperties webSocketProperties
    ) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.webSocketProperties = webSocketProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/lumisight/agent")
                .setAllowedOrigins(webSocketProperties.getAllowedOrigins());
    }
}
