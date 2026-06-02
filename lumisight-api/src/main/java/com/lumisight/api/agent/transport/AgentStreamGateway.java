package com.lumisight.api.agent.transport;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.support.AgentSessionDispatcher;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component
public class AgentStreamGateway {

    private final AgentSessionDispatcher sessionDispatcher;

    public AgentStreamGateway(AgentSessionDispatcher sessionDispatcher) {
        this.sessionDispatcher = sessionDispatcher;
    }

    public Disposable stream(AgentRunRequest request, AgentEventChannel channel) {
        return sessionDispatcher.stream(request)
                .doOnNext(channel::onEvent)
                .doOnError(channel::onError)
                .doOnComplete(channel::onComplete)
                .subscribe();
    }
}
