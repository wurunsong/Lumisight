package com.lumisight.api.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.support.AgentInteractionOrchestrator;
import com.lumisight.core.model.AgentEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/lumisight/agent")
public class CodeAssistantAgentController {

    private final AgentInteractionOrchestrator interactionOrchestrator;
    private final ObjectMapper objectMapper;

    public CodeAssistantAgentController(
            AgentInteractionOrchestrator interactionOrchestrator,
            ObjectMapper objectMapper
    ) {
        this.interactionOrchestrator = interactionOrchestrator;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentRunRequest request) {
        SseEmitter emitter = new SseEmitter(0L);

        interactionOrchestrator.stream(request)
                .doOnNext(event -> sendEvent(emitter, event))
                .doOnError(emitter::completeWithError)
                .doOnComplete(emitter::complete)
                .subscribe();

        return emitter;
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
}
