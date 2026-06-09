package com.lumisight.api.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.transport.AgentEventChannel;
import com.lumisight.api.agent.transport.AgentStreamGateway;
import com.lumisight.api.agent.transport.AgentTransportAdapter;
import com.lumisight.core.model.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/lumisight/agent")
@Slf4j
public class CodeAssistantAgentController implements AgentTransportAdapter {

    private final AgentStreamGateway streamGateway;
    private final ObjectMapper objectMapper;
    private final Map<String, ActiveSseConnection> activeConnections = new ConcurrentHashMap<>();

    public CodeAssistantAgentController(
            AgentStreamGateway streamGateway,
            ObjectMapper objectMapper
    ) {
        this.streamGateway = streamGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * stream负责建立长链接和流式响应用户提问
     * @param sessionId 会话id
     * @return sse流式响应
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("sessionId") String sessionId) {
        String normalizedSessionId = requireSessionId(sessionId);
        // 0 代表用不超时
        SseEmitter emitter = new SseEmitter(0L);
        Disposable disposable = streamGateway.subscribe(normalizedSessionId, new SseEventChannel(emitter, normalizedSessionId));
        ActiveSseConnection connection = new ActiveSseConnection(normalizedSessionId, emitter, disposable);
        replaceConnection(connection);
        emitter.onCompletion(() -> {
            unregisterConnection(connection);
            disposable.dispose();
        });
        emitter.onTimeout(() -> {
            log.warn("sse emitter timeout, closing session stream, sessionId={}", normalizedSessionId);
            unregisterConnection(connection);
            disposable.dispose();
        });
        emitter.onError(error -> {
            log.warn("sse emitter callback error, closing session stream, sessionId={}, message={}", normalizedSessionId, error == null ? "" : error.getMessage());
            unregisterConnection(connection);
            disposable.dispose();
        });
        // 在/run接口中，仍然用这个sse流回复用户（同一会话下）
        sendEvent(emitter, AgentEvent.state("", normalizedSessionId, 0, "STREAM", "connected", "SSE session stream connected."), normalizedSessionId);
        return emitter;
    }

    /**
     * run接口负责接收用户对话信息，并交给agent处理，但返回结果是依靠stream流式返回，run只返回一个状态
     * @param request 用户对话信息
     * @return 本次请求的状态
     */
    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> run(@RequestBody AgentRunRequest request) {
        AgentRunRequest normalized = normalizeCommandRequest(request);
        streamGateway.submit(normalized);
        return ResponseEntity.accepted().body(Map.of(
                "accepted", true,
                "sessionId", normalized.sessionId(),
                "userId", normalized.userId()
        ));
    }

    @Override
    public String protocol() {
        return "sse";
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event, String sessionId) {
        try {
            String data = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(data));
        } catch (IOException e) {
            if (isClientAbort(e)) {
                log.warn("sse client disconnected, skip event send, eventType={}, message={}", event.type(), e.getMessage());
                return;
            }
            log.error("failed to send sse event, encounter IO exception, message={}", e.getMessage());
            throw new IllegalStateException("failed to send sse event", e);
        } catch (Exception e) {
            if (isClientAbort(e) || isEmitterCompleted(e) || isAsyncResponseClosed(e)) {
                log.warn("sse emitter already closed, skip event send, eventType={}, message={}", event.type(), e.getMessage());
                return;
            }
            log.error("failed to send sse event, message={}", e.getMessage());
            throw new IllegalStateException("failed to send sse event", e);
        }
    }

    private AgentRunRequest normalizeCommandRequest(AgentRunRequest request) {
        String sessionId = request != null ? requireSessionId(request.sessionId()) : requireSessionId(null);
        String userId = request != null && request.userId() != null && !request.userId().isBlank()
                ? request.userId().trim()
                : "debug-user";
        return new AgentRunRequest(
                request == null ? null : request.taskType(),
                request == null ? null : request.repoRoot(),
                request == null ? null : request.question(),
                request == null ? null : request.skillPath(),
                userId,
                sessionId,
                request == null ? null : request.approveRiskyToolCall(),
                request == null ? null : request.interrupt(),
                request == null ? null : request.resume(),
                request == null ? null : request.includeRagContext(),
                request == null ? null : request.includeKnowledgeGraphContext(),
                request == null ? null : request.contextLimit(),
                request == null ? null : request.runMode(),
                request == null ? null : request.dialogueMode()
        );
    }

    private String requireSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        return sessionId.trim();
    }

    private void replaceConnection(ActiveSseConnection next) {
        ActiveSseConnection previous = activeConnections.put(next.sessionId(), next);
        if (previous == null) {
            return;
        }
        previous.disposable().dispose();
        safeComplete(previous.emitter(), "replaced_by_new_connection");
    }

    private void unregisterConnection(ActiveSseConnection connection) {
        activeConnections.remove(connection.sessionId(), connection);
    }

    private boolean isClientAbort(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("ClientAbortException")) {
                return true;
            }
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset by peer")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void safeComplete(SseEmitter emitter, String reason) {
        try {
            emitter.complete();
        } catch (Exception completeError) {
            if (isAsyncResponseClosed(completeError) || isClientAbort(completeError)) {
                log.warn("sse emitter complete ignored, reason={}, message={}", reason, completeError.getMessage());
                return;
            }
            log.error("failed to complete sse emitter, message={}", completeError.getMessage());
            throw completeError;
        }
    }

    private void safeCompleteWithError(SseEmitter emitter, Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception completeError) {
            if (isAsyncResponseClosed(completeError) || isClientAbort(completeError)) {
                log.warn("sse emitter completeWithError ignored, message={}", completeError.getMessage());
                return;
            }
            log.error("failed to complete sse emitter with error, message={}", completeError.getMessage());
            throw completeError;
        }
    }

    private boolean isAsyncResponseClosed(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("AsyncRequestNotUsableException")) {
                return true;
            }
            if (message != null && message.toLowerCase().contains("response not usable")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isEmitterCompleted(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("responsebodyemitter has already completed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record ActiveSseConnection(String sessionId, SseEmitter emitter, Disposable disposable) {
    }

    private final class SseEventChannel implements AgentEventChannel {
        private final SseEmitter emitter;
        private final String sessionId;

        private SseEventChannel(SseEmitter emitter, String sessionId) {
            this.emitter = emitter;
            this.sessionId = sessionId;
        }

        @Override
        public void onEvent(AgentEvent event) {
            sendEvent(emitter, event, sessionId);
        }

        @Override
        public void onError(Throwable error) {
            if (isClientAbort(error)) {
                log.warn("sse channel closed by client, suppress completeWithError, message={}", error == null ? "" : error.getMessage());
                return;
            }
            safeCompleteWithError(emitter, error);
        }

        @Override
        public void onComplete() {
            safeComplete(emitter, "channel_complete");
        }
    }
}
