package com.lumisight.core.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final Map<String, PermissionedAgentTool<?>> toolsByName;
    private final Map<AgentToolCategory, List<PermissionedAgentTool<?>>> toolsByCategory;

    public AgentToolRegistry(List<PermissionedAgentTool<?>> tools, List<AgentToolProvider> toolProviders) {
        List<PermissionedAgentTool<?>> allTools = new ArrayList<>(tools == null ? List.of() : tools);
        if (toolProviders != null) {
            for (AgentToolProvider provider : toolProviders) {
                allTools.addAll(provider.tools());
            }
        }
        this.toolsByName = allTools.stream().collect(Collectors.toMap(PermissionedAgentTool::toolName, Function.identity(), (a, b) -> b));
        this.toolsByCategory = new EnumMap<>(AgentToolCategory.class);
        for (AgentToolCategory category : AgentToolCategory.values()) {
            List<PermissionedAgentTool<?>> categoryTools = allTools.stream()
                    .filter(tool -> tool.category() == category)
                    .collect(Collectors.toList());
            this.toolsByCategory.put(category, categoryTools);
        }
        List<String> mcpToolNames = this.toolsByCategory.getOrDefault(AgentToolCategory.MCP, List.of()).stream()
                .map(PermissionedAgentTool::toolName)
                .toList();
        log.info("agent_tool_registry initialized, totalTools={}, mcpTools={}, mcpToolNames={}",
                this.toolsByName.size(), mcpToolNames.size(), mcpToolNames);
    }

    public PermissionedAgentTool<?> get(String toolName) {
        return toolsByName.get(toolName);
    }

    public List<PermissionedAgentTool<?>> getByCategory(AgentToolCategory category) {
        return toolsByCategory.getOrDefault(category, List.of());
    }
}
