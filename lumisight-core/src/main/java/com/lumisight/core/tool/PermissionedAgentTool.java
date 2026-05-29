package com.lumisight.core.tool;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;

import java.util.List;
import java.util.Map;

public interface PermissionedAgentTool {

    String toolName();

    AgentToolCategory category();

    AgentToolPermission permission();

    List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit);

    default List<ToolArgumentSpec> argumentSpecs() {
        return List.of();
    }

    default List<String> validateArgs(Map<String, Object> args) {
        return List.of();
    }
}
