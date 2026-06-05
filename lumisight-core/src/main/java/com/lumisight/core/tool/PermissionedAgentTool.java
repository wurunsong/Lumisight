package com.lumisight.core.tool;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;

import java.util.List;
import java.util.Map;

public interface PermissionedAgentTool<T> {

    String toolName();

    AgentToolCategory category();

    AgentToolPermission permission();

    Class<T> argsType();

    List<AgentContextItem> invoke(T args, int defaultLimit);

    default String description() {
        return "";
    }

    default List<ToolArgumentSpec> argumentSpecs() {
        return ToolArgsSupport.argumentSpecs(argsType());
    }

    default Map<String, Object> exampleArgs() {
        return ToolArgsSupport.exampleArgs(argsType());
    }

    default List<String> validateArgs(T args) {
        return List.of();
    }

    default boolean isReadOnly() {
        return switch (permission()) {
            case HYBRID_VECTOR_READ, KG_ONE_HOP_READ, METHOD_SOURCE_READ, BROWSER_READ, LOCAL_FS_READ, MEMORY_READ, TASK_READ, LSP_JAVA_READ, GIT_READ -> true;
            default -> false;
        };
    }

    default boolean isConcurrencySafe(T args) {
        return isReadOnly();
    }
}
