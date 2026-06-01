package com.lumisight.api.agent.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.support.AgentRequestMapper;
import com.lumisight.core.agent.CodeAssistantAgentService;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final AgentRequestMapper agentRequestMapper;
    private final CodeAssistantAgentService codeAssistantAgentService;
    private final Map<String, Disposable> subscriptions = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(
            ObjectMapper objectMapper,
            AgentRequestMapper agentRequestMapper,
            CodeAssistantAgentService codeAssistantAgentService
    ) {
        this.objectMapper = objectMapper;
        this.agentRequestMapper = agentRequestMapper;
        this.codeAssistantAgentService = codeAssistantAgentService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        AgentRunRequest req = objectMapper.readValue(message.getPayload(), AgentRunRequest.class);
        AgentRequest agentRequest = agentRequestMapper.toAgentRequest(req);

        Disposable old = subscriptions.remove(session.getId());
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }

        Disposable disposable = codeAssistantAgentService.run(agentRequest)
                .doOnNext(event -> sendEvent(session, event))
                .doOnError(error -> sendEvent(session, AgentEvent.error("websocket run failed: " + error.getMessage())))
                .doFinally(signalType -> subscriptions.remove(session.getId()))
                .subscribe();
        subscriptions.put(session.getId(), disposable);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Disposable disposable = subscriptions.remove(session.getId());
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private void sendEvent(WebSocketSession session, AgentEvent event) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String data = objectMapper.writeValueAsString(event);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(data));
                }
            }
        } catch (Exception ignored) {
            // send failures are ignored because connection may already be closed by peer.
        }
    }
}

