package com.lumisight.core.tool;

public enum AgentToolPermission {
    HYBRID_VECTOR_READ,
    KG_ONE_HOP_READ,
    METHOD_SOURCE_READ,
    MCP_CAPABILITY_CALL,
    BROWSER_READ,
    BROWSER_WRITE,
    // TODO(network-tools): add NETWORK_READ / NETWORK_WRITE when we introduce direct HTTP fetch tools.
    LOCAL_FS_READ,
    LOCAL_FS_WRITE,
    MEMORY_READ,
    MEMORY_WRITE,
    TASK_READ,
    TASK_WRITE,
    LSP_JAVA_READ,
    BUILD_COMPILE,
    GIT_READ,
    TODO_WRITE,
    AGENT_SPAWN
}
