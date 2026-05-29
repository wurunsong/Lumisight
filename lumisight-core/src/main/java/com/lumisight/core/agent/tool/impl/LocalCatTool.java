package com.lumisight.core.agent.tool.impl;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.ToolArgumentSpec;
import com.lumisight.core.agent.support.ToolArgumentValidators;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class LocalCatTool implements PermissionedAgentTool {

    @Override
    public String toolName() {
        return "cat";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.LOCAL;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.LOCAL_FS_READ;
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", true, "文件相对路径"),
                new ToolArgumentSpec("startLine", "integer", false, "起始行"),
                new ToolArgumentSpec("endLine", "integer", false, "结束行"),
                new ToolArgumentSpec("maxLines", "integer", false, "最大返回行数")
        );
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        return ToolArgumentValidators.requireText(args, "sourceFile", "sourceFile");
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String sourceFile = String.valueOf(args.get("sourceFile"));
            Integer startLine = intValue(args.get("startLine"));
            Integer endLine = intValue(args.get("endLine"));
            int maxLines = intValue(args.get("maxLines"), 300);
            Path file = LocalRepoPathSupport.resolveInRepo(root, sourceFile);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return error("cat", "文件不存在: " + sourceFile);
            }
            List<String> lines = Files.readAllLines(file);
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? Math.min(lines.size(), from + maxLines - 1) : Math.min(lines.size(), endLine);
            if (to < from) {
                return error("cat", "行号区间非法");
            }
            StringBuilder content = new StringBuilder();
            for (int i = from; i <= to; i++) {
                content.append(i).append(": ").append(lines.get(i - 1)).append("\n");
            }
            return List.of(new AgentContextItem(
                    "local",
                    "cat",
                    content.toString(),
                    Map.of("sourceFile", sourceFile, "lineRange", from + "-" + to)
            ));
        } catch (Exception e) {
            return error("cat", "cat 执行失败: " + e.getMessage());
        }
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private int intValue(Object value, int defaultValue) {
        Integer parsed = intValue(value);
        return parsed == null ? defaultValue : parsed;
    }

    private List<AgentContextItem> error(String sourceId, String message) {
        return List.of(new AgentContextItem("tool_error", sourceId, message, Map.of()));
    }
}
