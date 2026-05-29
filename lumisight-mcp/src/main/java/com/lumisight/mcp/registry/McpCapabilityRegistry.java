package com.lumisight.mcp.registry;

import com.lumisight.mcp.capability.McpCapability;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class McpCapabilityRegistry {

    private final Map<String, McpCapability> capabilityMap;

    public McpCapabilityRegistry(List<McpCapability> capabilities) {
        this.capabilityMap = capabilities.stream()
                .collect(Collectors.toMap(McpCapability::name, Function.identity(), (a, b) -> a));
    }

    public McpCapability get(String name) {
        return capabilityMap.get(name);
    }

    public List<Map<String, String>> list() {
        return capabilityMap.values().stream()
                .map(item -> Map.of(
                        "name", item.name(),
                        "description", item.description()
                ))
                .toList();
    }
}
