package com.lumisight.api.agent.transport;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.support.AgentSessionDispatcher;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * facade类，承接一下controller和dispatcher的逻辑
 */
@Component
public class AgentStreamGateway {

    private final AgentSessionDispatcher sessionDispatcher;

    public AgentStreamGateway(AgentSessionDispatcher sessionDispatcher) {
        this.sessionDispatcher = sessionDispatcher;
    }

    public Disposable subscribe(String sessionId, AgentEventChannel channel) {
        return sessionDispatcher.subscribe(sessionId)
                .doOnNext(channel::onEvent)
                .doOnError(channel::onError)
                .doOnComplete(channel::onComplete)
                .subscribe(
                        event -> { },
                        error -> { }
                );
    }

    public void submit(AgentRunRequest request) {
        sessionDispatcher.submit(request);
    }

    public void cancel(String sessionId, String reason) {
        sessionDispatcher.cancel(sessionId, reason);
    }
}
