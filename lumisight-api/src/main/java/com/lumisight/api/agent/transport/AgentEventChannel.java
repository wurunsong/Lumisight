package com.lumisight.api.agent.transport;

import com.lumisight.core.model.AgentEvent;

public interface AgentEventChannel {

    void onEvent(AgentEvent event);

    void onError(Throwable error);

    void onComplete();
}

