package com.lumisight.mcp.capability;

import java.util.Map;

public interface McpCapability {

    String name();

    String description();

    Map<String, Object> invoke(Map<String, Object> args);
}
