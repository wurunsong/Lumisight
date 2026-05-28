package com.lumisight.core.agent.tool;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AgentToolRegistry {

    private final Map<String, PermissionedAgentTool> toolsByName;
    private final Map<AgentToolCategory, List<PermissionedAgentTool>> toolsByCategory;

    public AgentToolRegistry(List<PermissionedAgentTool> tools) {
        this.toolsByName = tools.stream().collect(Collectors.toMap(PermissionedAgentTool::toolName, Function.identity()));
        this.toolsByCategory = new EnumMap<>(AgentToolCategory.class);
        for (AgentToolCategory category : AgentToolCategory.values()) {
            List<PermissionedAgentTool> categoryTools = tools.stream()
                    .filter(tool -> tool.category() == category)
                    .toList();
            this.toolsByCategory.put(category, categoryTools);
        }
    }

    public PermissionedAgentTool get(String toolName) {
        return toolsByName.get(toolName);
    }

    public List<PermissionedAgentTool> getByCategory(AgentToolCategory category) {
        return toolsByCategory.getOrDefault(category, List.of());
    }
}
