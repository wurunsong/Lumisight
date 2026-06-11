package com.lumisight.mcp.capability;

import java.util.Map;

/**
 * MCP capability extension point.
 * 当前仅保留抽象定义，具体 capability 由后续真正需要时再按场景补回。
 */
public abstract class McpCapability {

    public abstract String name();

    public abstract String description();

    public abstract Map<String, Object> invoke(Map<String, Object> args);
}
