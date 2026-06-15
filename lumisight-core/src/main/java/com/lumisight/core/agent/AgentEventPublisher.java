package com.lumisight.core.agent;

import com.lumisight.core.model.AgentEvent;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class AgentEventPublisher {

    private final Consumer<AgentEvent> emitter;
    private final List<AgentEvent> history = new ArrayList<>();

    AgentEventPublisher(Consumer<AgentEvent> emitter) {
        this.emitter = emitter;
    }

    static AgentEventPublisher streaming(FluxSink<AgentEvent> sink) {
        return new AgentEventPublisher(sink::next);
    }

    void emit(AgentEvent event) {
        history.add(event);
        emitter.accept(event);
    }

    List<AgentEvent> history() {
        return history;
    }
}
