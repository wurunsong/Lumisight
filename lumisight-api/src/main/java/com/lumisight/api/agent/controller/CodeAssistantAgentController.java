package com.lumisight.api.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.transport.AgentEventChannel;
import com.lumisight.api.agent.transport.AgentStreamGateway;
import com.lumisight.api.agent.transport.AgentTransportAdapter;
import com.lumisight.core.model.AgentEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/lumisight/agent")
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
        streamGateway.stream(request, new SseEventChannel(emitter));

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
        } catch (Exception e) {
            throw new IllegalStateException("failed to send sse event", e);
        }
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
            emitter.completeWithError(error);
        }

        @Override
        public void onComplete() {
            emitter.complete();
        }
    }
}
