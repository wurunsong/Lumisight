package com.lumisight.core.agent;

import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import reactor.core.publisher.Flux;

public interface AgentExecutionEngine {

    Flux<AgentEvent> execute(AgentRequest request);
}

