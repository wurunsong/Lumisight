package com.lumisight.core.agent.tool;

import com.lumisight.core.agent.model.AgentContextItem;

import java.util.List;
import java.util.Map;

public interface PermissionedAgentTool {

    String toolName();

    AgentToolCategory category();

    AgentToolPermission permission();

    List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit);
}
