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

    default String description() {
        return "";
    }

    default List<ToolArgumentSpec> argumentSpecs() {
        return List.of();
    }

    default List<String> validateArgs(Map<String, Object> args) {
        return List.of();
    }

    default boolean isReadOnly() {
        return switch (permission()) {
            case HYBRID_VECTOR_READ, KG_ONE_HOP_READ, METHOD_SOURCE_READ, LOCAL_FS_READ, LSP_JAVA_READ, GIT_READ -> true;
            default -> false;
        };
    }

    default boolean isConcurrencySafe(Map<String, Object> args) {
        return isReadOnly();
    }
}
