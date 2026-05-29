package com.lumisight.core.agent.multiagent.port;

import com.lumisight.core.agent.multiagent.model.SubAgentTask;

public interface TaskRouter {

    SubAgent route(SubAgentTask task);
}
