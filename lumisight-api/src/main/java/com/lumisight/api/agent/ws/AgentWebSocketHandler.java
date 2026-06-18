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
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class AgentWebSocketHandler extends TextWebSocketHandler implements AgentTransportAdapter {

    private final ObjectMapper objectMapper;
    private final AgentStreamGateway streamGateway;
    private final AgentWebSocketProperties webSocketProperties;
    private final Map<String, SessionSubscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<Long>> messageTimestamps = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    public AgentWebSocketHandler(
            ObjectMapper objectMapper,
            AgentStreamGateway streamGateway,
            AgentWebSocketProperties webSocketProperties
    ) {
        this.objectMapper = objectMapper;
        this.streamGateway = streamGateway;
        this.webSocketProperties = webSocketProperties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 超过最大连接数，直接关闭连接
        if (activeConnections.incrementAndGet() > webSocketProperties.getMaxConnections()) {
            activeConnections.decrementAndGet();
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        // 设置消息体大小
        session.setTextMessageSizeLimit(webSocketProperties.getMaxTextMessageSize());
        super.afterConnectionEstablished(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String requestId = "";
        try {
            // 检查这条连接/会话有没有超出消息频率限制
            if (!allowMessage(session.getId())) {
                sendProtocol(session, new WsAgentMessage("ERROR", requestId, null, "rate limit exceeded", System.currentTimeMillis()));
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            WsAgentCommand command = objectMapper.readValue(message.getPayload(), WsAgentCommand.class);
            String commandType = normalizeType(command.type());
            requestId = StringUtils.hasText(command.requestId()) ? command.requestId() : "";
            if ("PING".equals(commandType)) {
                sendProtocol(session, new WsAgentMessage("PONG", requestId, null, "pong", System.currentTimeMillis()));
                return;
            }
            AgentRunRequest req = normalizeRunRequest(commandType, command.request());
            if (!StringUtils.hasText(req.sessionId())) {
                sendProtocol(session, new WsAgentMessage("ERROR", requestId, null, "sessionId is required", System.currentTimeMillis()));
                return;
            }

            ensureSubscription(session, requestId, req.sessionId());
            sendProtocol(session, new WsAgentMessage("ACK", requestId, null, "accepted", System.currentTimeMillis()));
            streamGateway.submit(req);
        } catch (Exception e) {
            log.warn("agent_websocket command failed, sessionId={}, requestId={}, error={}", session.getId(), requestId, e.getMessage(), e);
            sendProtocol(session, new WsAgentMessage("ERROR", requestId, null, "command failed: " + e.getMessage(), System.currentTimeMillis()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 更新活跃连接数
        activeConnections.updateAndGet(v -> Math.max(0, v - 1));
        SessionSubscription subscription = subscriptions.remove(session.getId());
        messageTimestamps.remove(session.getId());
        if (subscription != null && !subscription.disposable().isDisposed()) {
            subscription.disposable().dispose();
        }
    }

    private AgentRunRequest normalizeRunRequest(String commandType, AgentRunRequest request) {
        AgentRunRequest base = request == null ? new AgentRunRequest(null, null, null, null, null, null, null, null, null, null, null, null, null, null) : request;
        boolean interrupt = "INTERRUPT".equals(commandType) || Boolean.TRUE.equals(base.interrupt());
        boolean resume = "RESUME".equals(commandType) || Boolean.TRUE.equals(base.resume());
        return new AgentRunRequest(
                base.taskType(),
                base.repoRoot(),
                base.question(),
                base.skillPath(),
                base.userId(),
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

    /**
     * 一个简单的滑动窗口限流
     * @param sessionId
     * @return
     */
    private boolean allowMessage(String sessionId) {
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        ConcurrentLinkedDeque<Long> deque = messageTimestamps.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>());
        while (true) {
            Long first = deque.peekFirst();
            if (first == null || first >= cutoff) {
                break;
            }
            deque.pollFirst();
        }
        // 检查1分钟内的消息数量是否超过阈值
        if (deque.size() >= webSocketProperties.getMessageRateLimitPerMinute()) {
            return false;
        }
        deque.addLast(now);
        return true;
    }

    private void sendEvent(WebSocketSession session, String requestId, AgentEvent event) {
        sendProtocol(session, new WsAgentMessage("EVENT", requestId, event, null, System.currentTimeMillis()));
    }

    private void ensureSubscription(WebSocketSession session, String requestId, String sessionId) {
        SessionSubscription current = subscriptions.get(session.getId());
        if (current != null && current.sessionId().equals(sessionId) && !current.disposable().isDisposed()) {
            current.channel().setRequestId(requestId);
            return;
        }
        if (current != null && !current.disposable().isDisposed()) {
            current.disposable().dispose();
        }
        WsEventChannel channel = new WsEventChannel(session, requestId);
        Disposable disposable = streamGateway.subscribe(sessionId, channel);
        subscriptions.put(session.getId(), new SessionSubscription(sessionId, channel, disposable));
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
        private volatile String requestId;

        private WsEventChannel(WebSocketSession session, String requestId) {
            this.session = session;
            this.requestId = requestId;
        }

        private void setRequestId(String requestId) {
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

    private record SessionSubscription(String sessionId, WsEventChannel channel, Disposable disposable) {
    }
}
