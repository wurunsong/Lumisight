package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.TeamAgentMessage;

import java.util.List;

public interface AgentMailboxBus {

    void send(String repoRoot, TeamAgentMessage message);

    List<TeamAgentMessage> readInbox(String repoRoot, String teamId, String agentId, boolean consume);
}
