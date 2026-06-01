package com.lumisight.api.agent.support;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.core.agent.AgentExecutionEngine;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class AgentInteractionOrchestrator {

    private final AgentExecutionEngine agentExecutionEngine;
    private final AgentRequestMapper agentRequestMapper;

    public AgentInteractionOrchestrator(
            AgentExecutionEngine agentExecutionEngine,
            AgentRequestMapper agentRequestMapper
    ) {
        this.agentExecutionEngine = agentExecutionEngine;
        this.agentRequestMapper = agentRequestMapper;
    }

    public Flux<AgentEvent> stream(AgentRunRequest runRequest) {
        AgentRequest request = agentRequestMapper.toAgentRequest(runRequest);
        return agentExecutionEngine.execute(request);
    }
}

