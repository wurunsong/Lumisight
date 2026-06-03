package com.lumisight.api.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.transport.AgentEventChannel;
import com.lumisight.api.agent.transport.AgentStreamGateway;
import com.lumisight.api.agent.transport.AgentTransportAdapter;
import com.lumisight.core.model.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/lumisight/agent")
@Slf4j
public class CodeAssistantAgentController implements AgentTransportAdapter {

    private final AgentStreamGateway streamGateway;
    private final ObjectMapper objectMapper;

    public CodeAssistantAgentController(
            AgentStreamGateway streamGateway,
            ObjectMapper objectMapper
    ) {
        this.streamGateway = streamGateway;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentRunRequest request) {
        AgentRunRequest normalized = normalizeIds(request);
        SseEmitter emitter = new SseEmitter(0L);
        Disposable disposable = streamGateway.stream(normalized, new SseEventChannel(emitter, normalized.sessionId()));
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            log.info("sse emitter timeout, disposing stream");
            streamGateway.cancel(normalized.sessionId(), "SSE connection timed out.");
            disposable.dispose();
        });
        emitter.onError(error -> {
            log.info("sse emitter callback error, disposing stream, message={}", error == null ? "" : error.getMessage());
            streamGateway.cancel(normalized.sessionId(), "SSE connection errored.");
            disposable.dispose();
        });

        return emitter;
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
                log.info("sse client disconnected, skip event send, eventType={}, message={}", event.type(), e.getMessage());
                streamGateway.cancel(sessionId, "SSE client disconnected while sending event.");
                return;
            }
            throw new IllegalStateException("failed to send sse event", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to send sse event", e);
        }
    }

    private AgentRunRequest normalizeIds(AgentRunRequest request) {
        String sessionId = request != null && request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId().trim()
                : UUID.randomUUID().toString();
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
                log.debug("sse emitter complete ignored, reason={}, message={}", reason, completeError.getMessage());
                return;
            }
            throw completeError;
        }
    }

    private void safeCompleteWithError(SseEmitter emitter, Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception completeError) {
            if (isAsyncResponseClosed(completeError) || isClientAbort(completeError)) {
                log.debug("sse emitter completeWithError ignored, message={}", completeError.getMessage());
                return;
            }
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
                log.info("sse channel closed by client, suppress completeWithError, message={}", error == null ? "" : error.getMessage());
                streamGateway.cancel(sessionId, "SSE client disconnected.");
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
