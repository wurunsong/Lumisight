package com.lumisight.core.tool.impl;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.support.ToolArgumentValidators;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.mcp.capability.McpCapability;
import com.lumisight.mcp.registry.McpCapabilityRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpCapabilityTool implements PermissionedAgentTool {

    private final McpCapabilityRegistry capabilityRegistry;

    public McpCapabilityTool(McpCapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public String toolName() {
        return "callMcpCapability";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.MCP;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.MCP_CAPABILITY_CALL;
    }

    @Override
    public String description() {
        return "调用 MCP 扩展能力，把请求转发给已注册的 capability，适合访问补充型外部能力。";
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        return ToolArgumentValidators.requireText(args, "capability", "capability");
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("capability", "string", true, "MCP能力名称"),
                new ToolArgumentSpec("args", "object", false, "MCP能力参数")
        );
    }

    @Override
    public Map<String, Object> exampleArgs() {
        return Map.of(
                "capability", "readRepoFileSnippet",
                "args", Map.of(
                        "sourceFile", "README.md",
                        "startLine", 1,
                        "endLine", 20
                )
        );
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        String capabilityName = args.get("capability") == null ? "" : String.valueOf(args.get("capability"));
        McpCapability capability = capabilityRegistry.get(capabilityName);
        if (capability == null) {
            return List.of(new AgentContextItem(
                    "mcp_error",
                    "unknown_capability",
                    "未知MCP能力: " + capabilityName,
                    Map.of("capability", capabilityName, "capabilityList", capabilityRegistry.list())
            ));
        }
        Map<String, Object> capabilityArgs = new HashMap<>();
        Object rawArgs = args.get("args");
        if (rawArgs instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                capabilityArgs.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        capabilityArgs.putIfAbsent("repoRoot", context.repoRoot());
        capabilityArgs.putIfAbsent("limit", defaultLimit);
        Map<String, Object> result = capability.invoke(capabilityArgs);
        return List.of(new AgentContextItem(
                "mcp",
                capabilityName,
                String.valueOf(result),
                result
        ));
    }

    @Tool(description = "调用 MCP 能力。capability 填能力名，args 填该能力参数。默认会注入 repoRoot。常用能力: grep/cat/ls/pwd/listRepoFiles/readRepoFileSnippet。")
    public List<AgentContextItem> callMcpCapability(
            @ToolParam(description = "能力名，例如 listRepoFiles/readRepoFileSnippet") String capability,
            @ToolParam(description = "能力参数对象") Map<String, Object> args
    ) {
        return invoke(Map.of("capability", capability, "args", args == null ? Map.of() : args), 5);
    }
}
