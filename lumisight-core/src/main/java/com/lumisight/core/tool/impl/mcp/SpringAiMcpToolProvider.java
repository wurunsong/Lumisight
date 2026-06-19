package com.lumisight.core.tool.impl.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolProvider;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SpringAiMcpToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiMcpToolProvider.class);

    private final ObjectProvider<SyncMcpToolCallbackProvider> callbackProvider;
    private final ObjectMapper objectMapper;
    private final ExternalMcpToolProperties properties;

    public SpringAiMcpToolProvider(
            ObjectProvider<SyncMcpToolCallbackProvider> callbackProvider,
            ObjectMapper objectMapper,
            ExternalMcpToolProperties properties
    ) {
        this.callbackProvider = callbackProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<PermissionedAgentTool<?>> tools() {
        // 这是新的 MCP 主接入点：不再像 legacy capability 那样走一个总入口工具，
        // 而是把 Spring AI MCP Client 暴露出来的每个外部 tool 动态包装成独立 AgentTool。
        if (!properties.isEnabled()) {
            log.info("spring_ai_mcp tools disabled by lumisight.mcp.tools.enabled=false");
            return List.of();
        }
        SyncMcpToolCallbackProvider provider = callbackProvider.getIfAvailable();
        if (provider == null) {
            log.info("spring_ai_mcp tool callback provider unavailable; set spring.ai.mcp.client.enabled=true and configure MCP connections to expose external tools");
            return List.of();
        }
        ToolCallback[] callbacks = provider.getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            log.info("spring_ai_mcp tool callback provider returned no tools");
            return List.of();
        }
        // 对 Agent 侧来说，后面只关心 PermissionedAgentTool；
        // 这里负责把 MCP callback 的协议细节收口成 Lumisight 自己的 tool 抽象。
        List<PermissionedAgentTool<?>> tools = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            if (definition == null || !StringUtils.hasText(definition.name())) {
                continue;
            }
            tools.add(new SpringAiMcpAgentTool(callback, definition, objectMapper, agentToolName(definition.name(), tools.size())));
        }
        log.info("spring_ai_mcp tools discovered, count={}, toolNames={}",
                tools.size(), tools.stream().map(PermissionedAgentTool::toolName).toList());
        return tools;
    }

    private String agentToolName(String mcpToolName, int index) {
        // MCP 原始 toolName 不一定适合直接暴露给 Agent，
        // 这里统一补前缀并归一化，避免和本地内置工具重名。
        String prefix = StringUtils.hasText(properties.getNamePrefix()) ? properties.getNamePrefix().trim() : "mcp_";
        String normalized = (prefix + mcpToolName)
                .replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_");
        if (!StringUtils.hasText(normalized)) {
            return "mcp_tool_" + index;
        }
        return normalized;
    }

    private static final class SpringAiMcpAgentTool implements PermissionedAgentTool<Map> {

        private final ToolCallback callback;
        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;
        private final String agentToolName;
        private final List<ToolArgumentSpec> argumentSpecs;
        private final boolean readOnly;

        private SpringAiMcpAgentTool(
                ToolCallback callback,
                ToolDefinition definition,
                ObjectMapper objectMapper,
                String agentToolName
        ) {
            this.callback = callback;
            this.definition = definition;
            this.objectMapper = objectMapper;
            this.agentToolName = agentToolName;
            this.argumentSpecs = parseArgumentSpecs(objectMapper, definition.inputSchema());
            this.readOnly = inferReadOnly(definition.name(), definition.description());
        }

        @Override
        public String toolName() {
            return agentToolName;
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
        public Class<Map> argsType() {
            return Map.class;
        }

        @Override
        public String description() {
            return "外部 MCP 工具，来自 Spring AI MCP Client。原始 MCP toolName=" + definition.name()
                    + "。" + safeText(definition.description());
        }

        @Override
        public List<ToolArgumentSpec> argumentSpecs() {
            return argumentSpecs;
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public List<AgentContextItem> invoke(Map args, int defaultLimit) {
            Map<String, Object> safeArgs = args == null ? Map.of() : args;
            try {
                ToolRuntimeScope.Context runtime = ToolRuntimeScope.required();
                String payload = objectMapper.writeValueAsString(safeArgs);
                // repoRoot / limit 这类运行时上下文不强依赖模型自己传，
                // 而是由 Lumisight 在调用 MCP tool 前统一注入到 ToolContext。
                String result = callback.call(payload, new ToolContext(Map.of(
                        "repoRoot", runtime.repoRoot(),
                        "limit", defaultLimit,
                        "agentToolName", agentToolName,
                        "mcpToolName", definition.name()
                )));
                return List.of(new AgentContextItem(
                        "mcp_tool",
                        agentToolName,
                        result == null ? "" : result,
                        Map.of(
                                "agentToolName", agentToolName,
                                "mcpToolName", definition.name(),
                                "inputSchema", safeText(definition.inputSchema()),
                                "readOnly", readOnly
                        )
                ));
            } catch (Exception e) {
                throw new IllegalStateException("外部 MCP 工具调用失败: " + definition.name() + " - " + e.getMessage(), e);
            }
        }

        private static List<ToolArgumentSpec> parseArgumentSpecs(ObjectMapper objectMapper, String inputSchema) {
            if (!StringUtils.hasText(inputSchema)) {
                return List.of();
            }
            try {
                // 这里不是做完整 JSON Schema 支持，只抽取 Agent prompt 真正需要的参数骨架：
                // 字段名、类型、是否必填、描述。
                JsonNode root = objectMapper.readTree(inputSchema);
                JsonNode properties = root.path("properties");
                if (!properties.isObject()) {
                    return List.of();
                }
                Set<String> required = new LinkedHashSet<>();
                JsonNode requiredNode = root.path("required");
                if (requiredNode.isArray()) {
                    requiredNode.forEach(node -> required.add(node.asText()));
                }
                List<ToolArgumentSpec> specs = new ArrayList<>();
                properties.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    specs.add(new ToolArgumentSpec(
                            entry.getKey(),
                            schemaType(value),
                            required.contains(entry.getKey()),
                            value.path("description").asText("")
                    ));
                });
                return specs;
            } catch (Exception ignored) {
                return List.of();
            }
        }

        private static String schemaType(JsonNode node) {
            JsonNode type = node.path("type");
            if (type.isTextual()) {
                return type.asText();
            }
            JsonNode anyOf = node.path("anyOf");
            if (anyOf.isArray()) {
                for (JsonNode candidate : anyOf) {
                    String candidateType = candidate.path("type").asText("");
                    if (StringUtils.hasText(candidateType) && !"null".equals(candidateType)) {
                        return candidateType;
                    }
                }
            }
            return "object";
        }

        private static boolean inferReadOnly(String name, String description) {
            // MCP 工具没有统一的“只读”标记时，只能先做启发式判断，
            // 供权限画像和调度层区分 read/write 风险。
            String text = (safeText(name) + " " + safeText(description)).toLowerCase(Locale.ROOT);
            if (text.matches(".*\\b(create|update|delete|remove|write|patch|merge|close|reopen|assign|unassign|lock|unlock|run|cancel|rerun|trigger)\\b.*")) {
                return false;
            }
            return text.matches(".*\\b(get|list|search|read|fetch|find|view|query)\\b.*");
        }

        private static String safeText(String text) {
            return text == null ? "" : text;
        }
    }
}
