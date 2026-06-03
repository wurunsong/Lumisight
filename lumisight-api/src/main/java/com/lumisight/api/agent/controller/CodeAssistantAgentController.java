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
        SseEmitter emitter = new SseEmitter(0L);
        Disposable disposable = streamGateway.stream(request, new SseEventChannel(emitter));
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            log.info("sse emitter timeout, disposing stream");
            disposable.dispose();
        });
        emitter.onError(error -> {
            log.info("sse emitter callback error, disposing stream, message={}", error == null ? "" : error.getMessage());
            disposable.dispose();
        });

        return emitter;
    }

    @Override
    public String protocol() {
        return "sse";
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) {
        try {
            String data = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(data));
        } catch (IOException e) {
            if (isClientAbort(e)) {
                log.info("sse client disconnected, skip event send, eventType={}, message={}", event.type(), e.getMessage());
                safeComplete(emitter, "client_abort_send");
                return;
            }
            throw new IllegalStateException("failed to send sse event", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to send sse event", e);
        }
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

        private SseEventChannel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onEvent(AgentEvent event) {
            sendEvent(emitter, event);
        }

        @Override
        public void onError(Throwable error) {
            if (isClientAbort(error)) {
                log.info("sse channel closed by client, suppress completeWithError, message={}", error == null ? "" : error.getMessage());
                safeComplete(emitter, "client_abort_channel");
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
