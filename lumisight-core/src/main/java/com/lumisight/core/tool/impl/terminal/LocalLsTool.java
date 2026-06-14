package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class LocalLsTool implements PermissionedAgentTool<LocalLsTool.Args> {

    public record Args(
            @ToolArg(description = "相对 repoRoot 的目录路径", example = "lumisight-core/src/main/java/com/lumisight/core") String path,
            @ToolArg(description = "最多返回条数", example = "50") Integer limit
    ) {
    }

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
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "列出仓库内目录内容，返回文件和子目录名称，适合先摸清目录结构再决定深入查看哪个文件。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            ToolRuntimeScope.Context context = ToolRuntimeScope.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String pathArg = args.path() == null ? "" : args.path();
            int limit = args.limit() == null ? (defaultLimit <= 0 ? 200 : defaultLimit) : args.limit();
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

    private List<AgentContextItem> error(String sourceId, String message) {
        return List.of(new AgentContextItem("tool_error", sourceId, message, Map.of()));
    }
}
