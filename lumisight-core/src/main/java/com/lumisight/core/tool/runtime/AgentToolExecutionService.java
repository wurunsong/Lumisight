package com.lumisight.core.tool.runtime;

import com.lumisight.core.hooks.runtime.ToolHookContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.support.ToolSchemaValidator;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentToolExecutionService {

    private static final int TOOL_MAX_RETRY = 2;

    private final AgentToolRegistry agentToolRegistry;

    public AgentToolExecutionService(AgentToolRegistry agentToolRegistry) {
        this.agentToolRegistry = agentToolRegistry;
    }

    public AgentToolExecutionResult execute(ToolDecision decision, Set<AgentToolPermission> enabledPermissions, int limit) {
        return execute(decision, enabledPermissions, limit, ToolHookContext.empty());
    }

    public boolean isConcurrencySafe(ToolDecision decision, Set<AgentToolPermission> enabledPermissions) {
        String requestedToolName = decision.toolName() == null ? "" : decision.toolName().trim();
        String toolName = normalizeToolName(requestedToolName);
        Map<String, Object> args = normalizeArgsForTool(requestedToolName, toolName, decision.args() == null ? Map.of() : decision.args());
        PermissionedAgentTool tool = agentToolRegistry.get(toolName);
        return tool != null
                && enabledPermissions.contains(tool.permission())
                && tool.isConcurrencySafe(args);
    }

    public AgentToolExecutionResult execute(ToolDecision decision, Set<AgentToolPermission> enabledPermissions, int limit, ToolHookContext hookContext) {
        String requestedToolName = decision.toolName() == null ? "" : decision.toolName().trim();
        String toolName = normalizeToolName(requestedToolName);
        Map<String, Object> args = normalizeArgsForTool(requestedToolName, toolName, decision.args() == null ? Map.of() : decision.args());
        PermissionedAgentTool tool = agentToolRegistry.get(toolName);
        if (tool == null) {
            return errorToolResult(toolName, "unknown_tool", "未知工具: " + toolName, Map.of("toolName", toolName, "requestedToolName", requestedToolName));
        }
        if (!enabledPermissions.contains(tool.permission())) {
            return errorToolResult(toolName, "permission_denied", "工具未启用: " + toolName, Map.of("toolName", toolName));
        }
        List<String> schemaErrors = ToolSchemaValidator.validate(args, tool.argumentSpecs());
        if (!schemaErrors.isEmpty()) {
            return errorToolResult(toolName, "schema_invalid", "工具参数结构校验失败", Map.of("errors", schemaErrors));
        }
        List<String> validationErrors = tool.validateArgs(args);
        if (!validationErrors.isEmpty()) {
            return errorToolResult(toolName, "invalid_args", "工具参数校验失败", Map.of("errors", validationErrors));
        }
        AgentToolExecutionResult primary = invokeWithRetry(tool, args, limit, TOOL_MAX_RETRY);
        if ("ok".equals(primary.status())) {
            return primary;
        }
        AgentToolExecutionResult fallback = tryFallback(toolName, args, limit, enabledPermissions);
        if (fallback != null) {
            return fallback;
        }
        return primary;
    }

    private String normalizeToolName(String requestedToolName) {
        if (!StringUtils.hasText(requestedToolName)) {
            return "";
        }
        return switch (requestedToolName.trim()) {
            case "read_file", "readFile", "open_file", "openFile", "get_file_content" -> "cat";
            case "read_directory", "list_directory", "get_directory_structure", "listDir" -> "ls";
            case "search_files", "search_in_files", "find_in_files" -> "grep";
            default -> requestedToolName.trim();
        };
    }

    private Map<String, Object> normalizeArgsForTool(String requestedToolName, String normalizedToolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        if (!StringUtils.hasText(requestedToolName) || requestedToolName.equals(normalizedToolName)) {
            return args;
        }
        if ("cat".equals(normalizedToolName)) {
            Map<String, Object> mapped = new HashMap<>(args);
            if (!mapped.containsKey("sourceFile")) {
                Object filePath = mapped.get("filePath");
                if (filePath == null) {
                    filePath = mapped.get("path");
                }
                if (filePath != null) {
                    mapped.put("sourceFile", String.valueOf(filePath));
                }
            }
            if (!mapped.containsKey("maxLines") && mapped.get("limit") != null) {
                mapped.put("maxLines", mapped.get("limit"));
            }
            return mapped;
        }
        if ("grep".equals(normalizedToolName)) {
            Map<String, Object> mapped = new HashMap<>(args);
            if (!mapped.containsKey("pattern")) {
                Object query = mapped.get("query");
                if (query == null) {
                    query = mapped.get("keyword");
                }
                if (query != null) {
                    mapped.put("pattern", String.valueOf(query));
                }
            }
            return mapped;
        }
        return args;
    }

    private AgentToolExecutionResult invokeWithRetry(PermissionedAgentTool tool, Map<String, Object> args, int limit, int maxRetry) {
        String toolName = tool.toolName();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                List<AgentContextItem> items = tool.invoke(args, limit);
                return new AgentToolExecutionResult(toolName, "ok", "工具执行成功", items, Map.of("count", items.size(), "attempt", attempt));
            } catch (Exception e) {
                lastError = e;
            }
        }
        return errorToolResult(toolName, "tool_invoke_failed", "工具调用失败: " + (lastError == null ? "unknown" : lastError.getMessage()), Map.of());
    }

    private AgentToolExecutionResult tryFallback(String toolName, Map<String, Object> args, int limit, Set<AgentToolPermission> enabledPermissions) {
        String fallbackName = fallbackToolName(toolName);
        if (!StringUtils.hasText(fallbackName)) {
            return null;
        }
        PermissionedAgentTool fallback = agentToolRegistry.get(fallbackName);
        if (fallback == null || !enabledPermissions.contains(fallback.permission())) {
            return null;
        }
        Map<String, Object> fallbackArgs = fallbackArgs(toolName, args);
        List<String> schemaErrors = ToolSchemaValidator.validate(fallbackArgs, fallback.argumentSpecs());
        if (!schemaErrors.isEmpty()) {
            return null;
        }
        List<String> errors = fallback.validateArgs(fallbackArgs);
        if (!errors.isEmpty()) {
            return null;
        }
        AgentToolExecutionResult result = invokeWithRetry(fallback, fallbackArgs, limit, 1);
        if ("ok".equals(result.status())) {
            return new AgentToolExecutionResult(
                    result.toolName(),
                    "ok",
                    "主工具失败，已回退到 " + fallbackName,
                    result.items(),
                    new HashMap<>(result.metrics())
            );
        }
        return null;
    }

    private String fallbackToolName(String toolName) {
        return switch (toolName) {
            case "cat" -> "fetchMethodSourceByLocation";
            case "grep", "ls", "pwd" -> "callMcpCapability";
            default -> null;
        };
    }

    private Map<String, Object> fallbackArgs(String toolName, Map<String, Object> args) {
        if ("cat".equals(toolName)) {
            return Map.of(
                    "sourceFile", String.valueOf(args.getOrDefault("sourceFile", "")),
                    "startLine", args.get("startLine") == null ? 1 : args.get("startLine"),
                    "endLine", args.get("endLine") == null ? 200 : args.get("endLine")
            );
        }
        if ("grep".equals(toolName)) {
            return Map.of(
                    "capability", "grep",
                    "args", Map.of(
                            "pattern", String.valueOf(args.getOrDefault("pattern", "")),
                            "filePattern", String.valueOf(args.getOrDefault("filePattern", "")),
                            "limit", args.get("limit") == null ? 50 : args.get("limit")
                    )
            );
        }
        if ("ls".equals(toolName)) {
            return Map.of(
                    "capability", "ls",
                    "args", Map.of(
                            "path", String.valueOf(args.getOrDefault("path", "")),
                            "limit", args.get("limit") == null ? 100 : args.get("limit")
                    )
            );
        }
        if ("pwd".equals(toolName)) {
            return Map.of("capability", "pwd", "args", Map.of());
        }
        return args;
    }

    private AgentToolExecutionResult errorToolResult(String toolName, String errorCode, String message, Map<String, Object> meta) {
        List<AgentContextItem> items = List.of(new AgentContextItem("tool_error", errorCode, message, meta));
        return new AgentToolExecutionResult(toolName, "error", message, items, Map.of("errorCode", errorCode));
    }
}
