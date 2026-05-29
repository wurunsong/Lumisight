package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class LocalLsTool implements PermissionedAgentTool {

    @Override
    public String toolName() {
        return "ls";
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
                new ToolArgumentSpec("path", "string", false, "相对 repoRoot 的目录路径"),
                new ToolArgumentSpec("limit", "integer", false, "最多返回条数")
        );
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String pathArg = args.get("path") == null ? "" : String.valueOf(args.get("path"));
            int limit = intValue(args.get("limit"), defaultLimit <= 0 ? 200 : defaultLimit);
            Path target = LocalRepoPathSupport.resolveInRepo(root, pathArg);
            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return error("ls", "目录不存在: " + pathArg);
            }
            try (Stream<Path> stream = Files.list(target)) {
                List<Map<String, Object>> entries = stream
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(limit)
                        .map(path -> Map.<String, Object>of(
                                "name", path.getFileName().toString(),
                                "type", Files.isDirectory(path) ? "dir" : "file",
                                "path", root.relativize(path).toString()
                        ))
                        .toList();
                return List.of(new AgentContextItem(
                        "local",
                        "ls",
                        "已列出目录内容",
                        Map.of("path", root.relativize(target).toString(), "entries", entries, "count", entries.size())
                ));
            }
        } catch (Exception e) {
            return error("ls", "ls 执行失败: " + e.getMessage());
        }
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private List<AgentContextItem> error(String sourceId, String message) {
        return List.of(new AgentContextItem("tool_error", sourceId, message, Map.of()));
    }
}
