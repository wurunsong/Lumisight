package com.lumisight.api.agent.transport;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.support.AgentSessionDispatcher;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

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
                // dispatcher emits from the agent worker thread. WebSocket/SSE IO must not run there,
                // otherwise STEER's worker interrupt can accidentally interrupt the client connection write.
                .publishOn(Schedulers.boundedElastic())
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
