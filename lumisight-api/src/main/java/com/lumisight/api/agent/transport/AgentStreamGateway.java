package com.lumisight.api.agent.transport;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.support.AgentInteractionOrchestrator;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component
public class AgentStreamGateway {

    private final AgentInteractionOrchestrator interactionOrchestrator;

    public AgentStreamGateway(AgentInteractionOrchestrator interactionOrchestrator) {
        this.interactionOrchestrator = interactionOrchestrator;
    }

    public Disposable stream(AgentRunRequest request, AgentEventChannel channel) {
        return interactionOrchestrator.stream(request)
                .doOnNext(channel::onEvent)
                .doOnError(channel::onError)
                .doOnComplete(channel::onComplete)
                .subscribe();
    }
}

