package com.lumisight.api.agent.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.transport.AgentEventChannel;
import com.lumisight.api.agent.transport.AgentStreamGateway;
import com.lumisight.api.agent.transport.AgentTransportAdapter;
import com.lumisight.api.agent.ws.dto.WsAgentCommand;
import com.lumisight.api.agent.ws.dto.WsAgentMessage;
import com.lumisight.core.model.AgentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler implements AgentTransportAdapter {

    private final ObjectMapper objectMapper;
    private final AgentStreamGateway streamGateway;
    private final Map<String, Disposable> subscriptions = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(
            ObjectMapper objectMapper,
            AgentStreamGateway streamGateway
    ) {
        this.objectMapper = objectMapper;
        this.streamGateway = streamGateway;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WsAgentCommand command = objectMapper.readValue(message.getPayload(), WsAgentCommand.class);
        String commandType = normalizeType(command.type());
        String requestId = StringUtils.hasText(command.requestId()) ? command.requestId() : "";
        if ("PING".equals(commandType)) {
            sendProtocol(session, new WsAgentMessage("PONG", requestId, null, "pong", System.currentTimeMillis()));
            return;
        }
        AgentRunRequest req = normalizeRunRequest(commandType, command.request());

        Disposable old = subscriptions.remove(session.getId());
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
        sendProtocol(session, new WsAgentMessage("ACK", requestId, null, "accepted", System.currentTimeMillis()));

        Disposable disposable = streamGateway.stream(req, new WsEventChannel(session, requestId));
        subscriptions.put(session.getId(), disposable);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Disposable disposable = subscriptions.remove(session.getId());
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private AgentRunRequest normalizeRunRequest(String commandType, AgentRunRequest request) {
        AgentRunRequest base = request == null ? new AgentRunRequest(null, null, null, null, null, null, null, null, null, null, null, null, null) : request;
        boolean interrupt = "INTERRUPT".equals(commandType) || Boolean.TRUE.equals(base.interrupt());
        boolean resume = "RESUME".equals(commandType) || Boolean.TRUE.equals(base.resume());
        return new AgentRunRequest(
                base.taskType(),
                base.repoRoot(),
                base.question(),
                base.skillPath(),
                base.sessionId(),
                base.approveRiskyToolCall(),
                interrupt,
                resume,
                base.includeRagContext(),
                base.includeKnowledgeGraphContext(),
                base.contextLimit(),
                base.runMode(),
                base.dialogueMode()
        );
    }

    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return "START";
        }
        return type.trim().toUpperCase();
    }

    private void sendEvent(WebSocketSession session, String requestId, AgentEvent event) {
        sendProtocol(session, new WsAgentMessage("EVENT", requestId, event, null, System.currentTimeMillis()));
    }

    private void sendProtocol(WebSocketSession session, WsAgentMessage message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String data = objectMapper.writeValueAsString(message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(data));
                }
            }
        } catch (Exception ignored) {
            // send failures are ignored because connection may already be closed by peer.
        }
    }

    @Override
    public String protocol() {
        return "websocket";
    }

    private final class WsEventChannel implements AgentEventChannel {
        private final WebSocketSession session;
        private final String requestId;

        private WsEventChannel(WebSocketSession session, String requestId) {
            this.session = session;
            this.requestId = requestId;
        }

        @Override
        public void onEvent(AgentEvent event) {
            sendEvent(session, requestId, event);
        }

        @Override
        public void onError(Throwable error) {
            sendEvent(session, requestId, AgentEvent.error("websocket run failed: " + error.getMessage()));
            subscriptions.remove(session.getId());
        }

        @Override
        public void onComplete() {
            subscriptions.remove(session.getId());
        }
    }
}
