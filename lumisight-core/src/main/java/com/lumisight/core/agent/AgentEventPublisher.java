package com.lumisight.core.agent;

import com.lumisight.core.model.AgentEvent;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;

final class AgentEventPublisher {

    private final FluxSink<AgentEvent> sink;
    private final List<AgentEvent> history = new ArrayList<>();

    AgentEventPublisher(FluxSink<AgentEvent> sink) {
        this.sink = sink;
    }

    void emit(AgentEvent event) {
        history.add(event);
        sink.next(event);
    }

    List<AgentEvent> history() {
        return history;
    }
}
